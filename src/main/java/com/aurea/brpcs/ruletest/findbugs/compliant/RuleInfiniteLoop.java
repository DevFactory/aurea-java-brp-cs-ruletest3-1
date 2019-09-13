package com.aurea.brpcs.ruletest.findbugs.compliant;

public class RuleInfiniteLoop {

    public static int test1(int n) {
        int i = 0;
        while (n >= 10) {
            n++;
            i++;
        }
        return i;
    }

    public static int test2(int N) {
        int n = N;
        int i = 0;
        while (n >= 10) {
            n++;
            i++;
        }
        return i;
    }

    public static int test4(int N) {
        int n = N;
        int i = 0;
        while (n >= 10) {
            n = n + 1;
            i = i + 1;
        }
        return i;
    }

    public static void test6() {
        for (int x = 0; x < 10; x++) {
            doNothing();
        }
    }

    private static void doNothing() {

    }

}
