package edu.psu.compbio.seqcode.gse.gsebricks.verbs;


public interface Combiner<A,B,C> {

    public C execute(A a, B b);

}