package edu.psu.compbio.seqcode.gse.projects.gps.utilities;

import java.util.*;

import edu.psu.compbio.seqcode.genome.location.Point;
import edu.psu.compbio.seqcode.genome.location.Region;
import edu.psu.compbio.seqcode.genome.location.StrandedPoint;
import edu.psu.compbio.seqcode.gse.datasets.seqdata.*;
import edu.psu.compbio.seqcode.gse.gsebricks.verbs.chipseq.*;
import edu.psu.compbio.seqcode.gse.projects.gps.DeepSeqExpt;
import edu.psu.compbio.seqcode.gse.utils.Pair;
import edu.psu.compbio.seqcode.gse.viz.metaprofile.*;

/**
 * FivePrimeProfiler profiles the occurrence of sequencing reads around points
 *  
 * @author: tdanford
 * Date: Aug 19, 2008
 */

public class FivePrimeProfiler implements PointProfiler<Point,PointProfile> {
	
	private BinningParameters params;
	private List<SeqExpander> expanders=null;
	private DeepSeqExpt expt=null;
	private char strand;
	private double pbMax=2;
	
	public FivePrimeProfiler(BinningParameters ps, SeqExpander exp, char strand, double pbMax) {
		params = ps;
		expanders = new ArrayList<SeqExpander>(); 
		expanders.add(exp);
		this.strand = strand;
		this.pbMax = pbMax;
	}
	public FivePrimeProfiler(BinningParameters ps, List<SeqExpander> exps, char strand, double pbMax) {
		params = ps;
		expanders = exps;
		this.strand = strand;
		this.pbMax = pbMax;
	}
	public FivePrimeProfiler(BinningParameters ps, DeepSeqExpt exp, char strand, double pbMax) {
		params = ps;
		expt = exp;
		this.strand = strand;
		this.pbMax = pbMax;
	}

	public BinningParameters getBinningParameters() {
		return params;
	}

	public PointProfile execute(Point a) {
		int window = params.getWindowSize();
		int upstream = window/2;
		int downstream = window-upstream-1;
		char pointStrand = '+';
		
		if(a instanceof StrandedPoint)
			pointStrand = ((StrandedPoint)a).getStrand();
		boolean wantPosStrandReads = this.strand=='+';
		if(pointStrand == '-')
			wantPosStrandReads = !wantPosStrandReads;
		char wantedStrand = wantPosStrandReads?'+':'-';
		
		int start = pointStrand == '+' ?  Math.max(1, a.getLocation()-upstream) : Math.max(1, a.getLocation()-downstream);
		int end = pointStrand == '+' ?  Math.min(a.getLocation()+downstream, a.getGenome().getChromLength(a.getChrom())) : Math.min(a.getLocation()+upstream, a.getGenome().getChromLength(a.getChrom()));
		Region query = new Region(a.getGenome(), a.getChrom(), start, end);
		
		
		double[] array = new double[params.getNumBins()];
		for(int i = 0; i < array.length; i++) { array[i] = 0; }
		double[] exparray = new double[params.getNumBins()];
		for(int i = 0; i < exparray.length; i++) { exparray[i] = 0; }
		
		if(expt!=null){
			Pair<ArrayList<Integer>, ArrayList<Float>> sbc = expt.loadStrandedBaseCounts(query, wantedStrand);
			for(int x=0; x<sbc.car().size(); x++){
				int pos = sbc.car().get(x);
				float weight = sbc.cdr().get(x);
				int hit5Prime = pos-start;
				if(pointStrand=='-')
					hit5Prime = end-pos;
				exparray[params.findBin(hit5Prime)]+=weight;
			}
		}else if (expanders!=null){
			for(SeqExpander expander : expanders){
				Iterator<SeqHit> hits = expander.execute(query);
				while(hits.hasNext()){
					SeqHit hit = hits.next();
					if (hit.getStrand()==wantedStrand){  //only count one strand
						if (start<=hit.getFivePrime() && end>=hit.getFivePrime()){
							int hit5Prime = hit.getFivePrime()-start;
							if(pointStrand=='-')
								hit5Prime = end-hit.getFivePrime();
							exparray[params.findBin(hit5Prime)]+=hit.getWeight();
						}
					}				
				}
			}
		}
		
		for(int i = 0; i < array.length; i++) { 
			if(exparray[i]>pbMax)
				exparray[i]=pbMax;
			array[i] += exparray[i];
		}
		return new PointProfile(a, params, array, (a instanceof StrandedPoint));
	}
	
	
	public void cleanup(){
		if(expanders!=null){
			for(SeqExpander e : expanders)
				e.close();
		}
		if(expt!=null)
			expt.closeLoaders();
	}
}
