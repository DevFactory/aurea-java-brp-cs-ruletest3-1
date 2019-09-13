package com.aurea.brpcs.ruletest.findbugs.noncompliant;



public class EmptyStatementUsageCheck {

    public static void main(String[] args) {
        System.out.println();;
    }

    public void nonCompliant() {
        ;;
    }
}
