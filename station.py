# Written by Adam Inskip, 22929581
# Written for CITS3002 Computer Networks, Project 2020 Semester 1

import select, socket, sys, queue, os
from datetime import datetime
from urllib import parse

# get port info
stationName = str(sys.argv[1])
tcp_port = int(sys.argv[2])
udp_host = int(sys.argv[3])
neighbourSlicer = slice(4, len(sys.argv))
udpNeighbours = sys.argv[neighbourSlicer]
udpNeighbours = list(map(int,udpNeighbours)) # convert to integers
host = 'localhost'

# create TCP server socket
tcp_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
tcp_server.setblocking(0)
tcp_server.bind((host, tcp_port))
tcp_server.listen(5)

# create UDP server socket
udpBufferSize = 1024
udp_server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
udp_server.bind((host, udp_host))

# define inputs/outputs for select()
inputs = [tcp_server, udp_server]
outputs = []
message_queues = {}

# time of TCP query
# hardcode for testing purposes
# TIME = datetime.strptime("08:15", '%H:%M').time()
# actual application - use current time on startup
TIME = datetime.now()
TIME = datetime.strptime(str(TIME.hour) + ":" + str(TIME.minute), '%H:%M').time()

# define a structure to store completed timefind packets
completeTF = {
    "RFcount": 0, # store the total number of completed RF packets so we can track returning TFs
    "TFresponse": [] # add completed TFs to this
}

sentRF = -1

def readTimetable(stationName):
    filename = "tt-" + stationName
    f = open(filename, "r")
    TIMETABLE = f.read().split()
    TT_MODTIME = datetime.fromtimestamp(os.stat(filename).st_mtime).strftime('%Y-%m-%d-%H:%M')
    return (TIMETABLE, TT_MODTIME)

TIMETABLE, MODTIME = readTimetable(stationName)

def findNextDeparture(timetable, stationName, destination, time):
    # check for file changes
    filename = "tt-" + stationName
    modtime = datetime.fromtimestamp(os.stat(filename).st_mtime).strftime('%Y-%m-%d-%H:%M')
    if modtime > MODTIME:
        # reread timetable
        timetable, newMT = readTimetable(stationName)
    for t in timetable:
        if destination in t:
            data = t.split(',')
            departTime = datetime.strptime(data[0], '%H:%M').time()
            if time < departTime:
                # found a valid departure time
                return t
    # if no valid time, report error (0)
    return 0

def getDestination(data):
    lines = data.splitlines()
    requestLine = lines[0].split()
    target = requestLine[1]
    if '?' in target and not target.endswith('?'):
        destination = parse.parse_qs(parse.urlparse(target).query)['to'][0]
        return destination
    else:
        return 0

# route finding protocol (RF)
def buildRouteFind(RF):
    return str.encode('{},{},{},{},{},{},{},{}'.format(RF['type'], RF['source'], RF['sourcePort'], RF['dest'], RF['path'], RF['portPath'], RF['complete'], RF["invalid"]))

def decodeRouteFind(RF):
    RF = RF.decode()
    incomingRFdata = RF.split(',')
    incomingRFtype = str(incomingRFdata[0])
    incomingRFsource = str(incomingRFdata[1])
    incomingRFsourcePort = int(incomingRFdata[2])
    incomingRFdestination = str(incomingRFdata[3])
    incomingRFpath = str(incomingRFdata[4])
    incomingRFportPath = str(incomingRFdata[5])
    incomingRFcomplete = int(incomingRFdata[6])
    incomingRFinvalid = int(incomingRFdata[7])
    return {
        "type": incomingRFtype,
        "source": incomingRFsource,
        "sourcePort": incomingRFsourcePort,
        "dest": incomingRFdestination,
        "path": incomingRFpath,
        "portPath": incomingRFportPath,
        "complete": incomingRFcomplete,
        "invalid": incomingRFinvalid
    }

# time finding protocol (TF)
def buildTimeFind(TF):
    return str.encode('{},{},{},{},{},{}'.format(TF['type'], TF['path'], TF['portPath'], TF['arrivalTime'], TF['complete'], TF["invalid"]))

def decodeTimeFind(TF):
    TF = TF.decode()
    incomingTFdata = TF.split(',')
    incomingTFtype = str(incomingTFdata[0])
    incomingTFpath = str(incomingTFdata[1])
    incomingTFportPath = str(incomingTFdata[2])
    incomingTFarrival = str(incomingTFdata[3])
    incomingTFcomplete = int(incomingTFdata[4])
    incomingTFinvalid = int(incomingTFdata[5])
    return {
        "type": "TF",
        "path": incomingTFpath,
        "portPath": incomingTFportPath,
        "arrivalTime": incomingTFarrival,
        "complete": incomingTFcomplete,
        "invalid": incomingTFinvalid
    }

# count route protocol (CR)
def buildCR(CR):
    return str.encode('{},{},{},{}'.format(CR['type'], CR['path'], CR['portPath'], CR['count']))

def decodeCR(CR):
    CR = CR.decode()
    incomingCRdata = CR.split(',')
    incomingCRtype = str(incomingCRdata[0])
    incomingCRpath = str(incomingCRdata[1])
    incomingCRportPath = str(incomingCRdata[2])
    incomingCRcount = int(incomingCRdata[3])
    return {
        "type": incomingCRtype,
        "path": incomingCRpath,
        "portPath": incomingCRportPath,
        "count": incomingCRcount
    }

def handleTCP(data):
    global sentRF
    destination = getDestination(data)
    if destination == 0:
        # No valid destination supplied
        return """HTTP/1.0 400 Bad Request
Content-Length: 59
Content-Type: text/html
Connection: Closed\n
<!doctype html>
<html>
<body>400 Bad Request</body>
</html>"""
    else:
        # valid destination supplied
        # send initial UDP packet flood, searching for route to destination
        # internal representation
        initialRF = {
            "type": "RF",
            "source": stationName,
            "sourcePort": udp_host,
            "dest": destination,
            "path": str(stationName),
            "portPath": str(udp_host),
            "complete": 0,
            "invalid": 0
        }
        # begin tracking number of active, unique RF packets in network
        sentRF = 0
        for n in udpNeighbours:
            udp_server.sendto(buildRouteFind(initialRF), (host,n))
            sentRF += 1
        return 0

def generateJourneyHTTP():
    optimalTF = 0
    for TF in completeTF["TFresponse"]:
        if TF != 0:
            # initialize optimalTF to first valid TF response
            optimalTF = TF
            break

    if optimalTF == 0:
        # no valid journeys available
        responseBody = """<!doctype html>
<html>
<body>No valid journeys are available to that location today. Check tomorrow's timetable instead.</body>
</html>"""
        responseHeader = """HTTP/1.0 200 OK
Content-Length: {}
Content-Type: text/html
Connection: Closed\n""".format(len(responseBody))
        return responseHeader + '\n' + responseBody

    for TF in completeTF["TFresponse"]:
        # if this executes, we know there must be at least one valid journey
        if TF != 0:
            if datetime.strptime(TF["arrivalTime"], '%H:%M').time() < datetime.strptime(optimalTF["arrivalTime"], '%H:%M').time():
                optimalTF = TF


    firstLeg = findNextDeparture(TIMETABLE, stationName, optimalTF["path"].split('/')[1],TIME).split(',')
    departTime = firstLeg[0]
    busNo = firstLeg[1]
    stopNo = firstLeg[2]
    destination = optimalTF["path"].split('/')[len(optimalTF["path"].split('/')) - 1]
    responseBody = """<!doctype html>
<html>
<body>Departure: {} from {} at {}<br>Arrival: {} at {}
</body>
</html>""".format(busNo, stopNo, departTime, optimalTF["arrivalTime"], destination)
    responseHeader = """HTTP/1.0 200 OK
Content-Length: {}
Content-Type: text/html
Connection: Closed\n""".format(len(responseBody))
    return responseHeader + '\n' + responseBody

def backtraceRF(msg):
    portPath = msg["portPath"].split('/')
    portPath = list(map(int, portPath))  # convert to integers
    ppIndex = portPath.index(udp_host)
    udp_server.sendto(buildRouteFind(msg), (host, portPath[ppIndex-1]))

def backtraceTF(msg):
    portPath = msg["portPath"].split('/')
    portPath = list(map(int, portPath))  # convert to integers
    ppIndex = portPath.index(udp_host)
    udp_server.sendto(buildTimeFind(msg), (host, portPath[ppIndex-1]))

def backtraceCR(msg):
    portPath = msg["portPath"].split('/')
    portPath = list(map(int, portPath))  # convert to integers
    ppIndex = portPath.index(udp_host)
    udp_server.sendto(buildCR(msg), (host, portPath[ppIndex-1]))

def handleUDP(byteAddressPair):
    global sentRF
    udp_msg = bytesAddressPair[0]
    udp_addr = bytesAddressPair[1]
    if udp_msg.decode()[0:2] == 'RF':
        msg = decodeRouteFind(udp_msg)
        if msg['complete'] == 0:
            if stationName not in msg['path']:
                # still searching for a route
                # check if destination station is current station
                if msg['dest'] == stationName:
                    # complete the path
                    msg['path'] = msg['path'] + '/' + stationName
                    msg['portPath'] = msg['portPath'] + '/' + str(udp_host)
                    msg['complete'] = 1
                    # send complete path back to source via neighbours
                    backtraceRF(msg)
                else:
                    # still looking for destination, append path and send to neighbours
                    msg['path'] = msg['path'] + '/' + stationName
                    msg['portPath'] = msg['portPath'] + '/' + str(udp_host)
                    outboundRF = 0
                    for n in udpNeighbours:
                        if n != msg['portPath'].split('/')[len(msg['portPath'].split('/'))-2]: # don't send to previous neighbour
                            udp_server.sendto(buildRouteFind(msg), (host,n))
                            outboundRF += 1
                    newCR = {
                        "type": "CR",
                        "path": msg["path"],
                        "portPath": msg['portPath'],
                        "count": outboundRF-1 # accounting for new packets sent, but one was already in the system when this station received it
                    }
                    backtraceCR(newCR)
            else:
                # packet still searching, but has already visited this station
                # mark as complete but invalid, and return to source
                msg["complete"] = 1
                msg["invalid"] = 1
                backtraceRF(msg)
        else:
            # received a completed routeFind packet
            if int(msg["portPath"].split('/')[0]) != udp_host:
                backtraceRF(msg)
            else:
                if not msg["invalid"]:
                    # complete RF packet made it back to source station
                    completePath = msg['path']
                    completePortPath = msg['portPath']
                    sentRF -= 1  # one of our packets has arrived back
                    # increase count of completed routefinds
                    completeTF["RFcount"] += 1
                    # create and send timeFind
                    nextDep = findNextDeparture(TIMETABLE, stationName, completePath.split('/')[1], TIME)
                    if nextDep == 0:
                        # no valid route beyond source
                        completeTF["TFresponse"].append(0)
                    else:
                        nextDep = nextDep.split(',')
                        initialTF = {
                            "type": "TF",
                            "path": completePath,
                            "portPath": completePortPath,
                            "arrivalTime": nextDep[3],
                            "complete": 0,
                            "invalid": 0
                        }
                        udp_server.sendto(buildTimeFind(initialTF), (host, int(initialTF["portPath"].split('/')[1])))
                else:
                    # invalid routeFind returned
                    # discard packet, decrease count of active packets by 1
                    sentRF -= 1
    elif udp_msg.decode()[0:2] == 'TF':
        msg = decodeTimeFind(udp_msg)
        if not msg["complete"]:
            if stationName == msg["path"].split('/')[len(msg["path"].split('/'))-1]:
                msg["complete"] = 1
                # backtrace to source
                if int(msg["portPath"].split('/')[0]) != udp_host:
                    backtraceTF(msg)
            else:
                # find next station
                currentStationIndex = msg["path"].split('/').index(stationName)
                arrivalTime = datetime.strptime(msg["arrivalTime"], '%H:%M').time()
                # find next time to next station on journey
                nextDep = findNextDeparture(TIMETABLE, stationName, msg["path"].split('/')[currentStationIndex+1], arrivalTime)
                if nextDep == 0:
                    # mark packet as complete but invalid, and begin backtrace to source
                    msg["complete"] = 1
                    msg["invalid"] = 1
                    backtraceTF(msg)
                else:
                    nextDep = nextDep.split(',')
                    msg["arrivalTime"] = nextDep[3]
                    udp_server.sendto(buildTimeFind(msg), (host, int(msg["portPath"].split('/')[currentStationIndex+1])))
        else:
            # received complete timeFind packet
            if int(msg["portPath"].split('/')[0]) != udp_host:
                backtraceTF(msg)
            else:
                # complete TF packet arrived back at source
                if not msg["invalid"]:
                    # completed, valid timefind returned
                    completeTF["TFresponse"].append(msg)
                else:
                    # invalid timefind i.e. no possible route
                    completeTF["TFresponse"].append(0)
    elif udp_msg.decode()[0:2] == 'CR':
        msg = decodeCR(udp_msg)
        if int(msg["portPath"].split('/')[0]) != udp_host:
            backtraceCR(msg)
        else:
            # CR arrived at source
            msg = decodeCR(udp_msg)
            sentRF += msg['count']


# define a structure to store completed timefind packets
# completeTF = {
#     "RFcount": 0, # store the total number of completed RF packets so we can track returning TFs
#     "TFresponse": [] # add completed TFs to this
# }

finalTCPout = 0

while inputs:
    if sentRF == 0 and completeTF["RFcount"] == len(completeTF["TFresponse"]):
        # all RF and TF packets have completed their journeys, time to output TCP response
        response = generateJourneyHTTP()
        message_queues[finalTCPout].put(str.encode(response))
        if finalTCPout not in outputs:
            outputs.append(finalTCPout)

        # now reset everything to allow another request to process
        sentRF = -1
        completeTF = {
            "RFcount": 0,  # store the total number of completed RF packets so we can track returning TFs
            "TFresponse": []  # add completed TFs to this
        }
        finalTCPout = 0

    readable, writable, exceptional = select.select(inputs, outputs, inputs)
    for s in readable:
        if s is tcp_server:
            connection, client_address = s.accept()
            connection.setblocking(0)
            inputs.append(connection)
            message_queues[connection] = queue.Queue()
        elif s is udp_server:
            bytesAddressPair = udp_server.recvfrom(udpBufferSize)
            handleUDP(bytesAddressPair)
        else:
            data = s.recv(1024)
            if data:
                TIME = datetime.now()
                TIME = datetime.strptime(str(TIME.hour) + ":" + str(TIME.minute), '%H:%M').time()
                response = handleTCP(data.decode())
                if response != 0: # only respond immediately if an invalid request was provided
                    message_queues[s].put(str.encode(response))
                    if s not in outputs:
                        outputs.append(s)
                else:
                    # store output tcp socket for later writing the output
                    finalTCPout = s
            else:
                if s in outputs:
                    outputs.remove(s)
                inputs.remove(s)
                s.close()
                del message_queues[s]

    for s in writable:
        try:
            next_msg = message_queues[s].get_nowait()
        except queue.Empty:
            outputs.remove(s)
        else:
            s.send(next_msg)

    for s in exceptional:
        inputs.remove(s)
        if s in outputs:
            outputs.remove(s)
        s.close()
        del message_queues[s]

