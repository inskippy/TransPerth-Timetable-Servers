Project completed for University of Western Australia CITS3002 Computer Networks unit. Written in Semester 1, 2020 by Adam Inskip.

Instructions for running:

Implemented in Python and Java. Each program achieves the same result, but in a different language.

Run from terminal with following syntax for python/java implementations, preferably in a startstations.sh shellscript
	python3 station.py StationName TCP_PORT UDP_PORT [UDP_NEIGHBOURS] &
	java station StationName TCP_PORT UDP_PORT [UDP_NEIGHBOURS] &

Compile the java file using
	javac station.java

Run ./startstations.sh
Use myForm.html with the provided timetable files to view the implementation. Timetable files and new shellscripts can be
created if you wish to use the software for other timetables, such as the TransPerth data which this was designed to use.

Ensure the timetable files are located in the same directory as station.py and station.class
