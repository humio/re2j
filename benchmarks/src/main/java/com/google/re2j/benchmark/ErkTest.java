package com.google.re2j.benchmark;

public class ErkTest {
    public static void main0(String[] args) {
	BenchmarkSubMatch b = new BenchmarkSubMatch();
	b.setup();
	long t0 = System.nanoTime();
	for (int i = 0; i < 100000; i++) {
	    b.findPhoneNumbers();
	    if (i%100 == 0) {
		long t1 = System.nanoTime();
		long nsPerIter = (t1-t0)/100;
		System.out.println("#"+i+"\t"+nsPerIter+" ns/op");
		t0 = t1;
	    }
	}
    }

    public static void main(String[] args) {
	BenchmarkFullMatch b = new BenchmarkFullMatch();
	b.setup();
	long t0 = System.nanoTime();
	for (int i = 0; i < 1000000; i++) {
	    b.matched(); // notMatched
	    if (i%1000 == 0) {
		long t1 = System.nanoTime();
		long nsPerIter = (t1-t0)/1000;
		System.out.println("#"+i+"\t"+nsPerIter+" ns/op");
		t0 = t1;
	    }
	}
    }
}
