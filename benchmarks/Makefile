run:
	javac -classpath build/classes/java/main/:/home/erik/.gradle/caches/modules-2/files-2.1/org.openjdk.jmh/jmh-core/1.21/442447101f63074c61063858033fbfde8a076873/jmh-core-1.21.jar \
	-d build/classes/java/main/ src/main/java/com/google/re2j/benchmark/ErkTest.java src/main/java/com/google/re2j/benchmark/Bench*.java
	java -classpath build/classes/java/main/:build/resources/main:../build/libs/re2j.jar com.google.re2j.benchmark.ErkTest
