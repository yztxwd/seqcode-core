package edu.psu.compbio.seqcode.projects.sequtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import edu.psu.compbio.seqcode.deepseq.StrandedBaseCount;
import edu.psu.compbio.seqcode.deepseq.StrandedPair;
import edu.psu.compbio.seqcode.deepseq.experiments.ControlledExperiment;
import edu.psu.compbio.seqcode.deepseq.experiments.ExperimentManager;
import edu.psu.compbio.seqcode.deepseq.experiments.ExptConfig;
import edu.psu.compbio.seqcode.genome.Genome;
import edu.psu.compbio.seqcode.genome.GenomeConfig;
import edu.psu.compbio.seqcode.genome.location.Region;
import edu.psu.compbio.seqcode.gse.gsebricks.verbs.location.ChromosomeGenerator;
import edu.psu.compbio.seqcode.gse.utils.ArgParser;
import edu.psu.compbio.seqcode.gse.utils.RealValuedHistogram;
import edu.psu.compbio.seqcode.projects.multigps.framework.MultiGPSConfig;

/**
 * SeqLibrarySize aims to estimate the size of the initial library that produced a sequencing experiment. 
 * The method fits a zero-truncated negative binomial to a histogram of fragment occurrences. This approach 
 * will only yield an accurate estimate of initial library size if we assume that each of our reads was produced
 * by a DNA fragment that was unique in the initial library. This of course is not true for many experiment. In 
 * particular, functional genomics enrichment assays (ChIP-seq, DNaseI-seq, etc) can be expected to contain multiple
 * identical DNA molecules in the initial library, as a consequence of the enrichment processes. Our method therefore
 * works by focusing analysis on the counts of a subset of reads that are more likley to have been generated by 
 * unique DNA molecules in the initial library. Under our assumptions, reads that are located in the lowest-density
 * regions on the genome are good candidates.   
 *  
 * @author mahony
 *
 */
public class SeqLibrarySize {

	GenomeConfig gconfig;
	MultiGPSConfig config;
	ExptConfig econfig;
	Genome gen;
	ExperimentManager manager=null;
	float[] startcounts =null;
	float densityWindow = 1000;  //Use this genomic window size to assess the density of reads
	static double testQuantile = 0.10; //Base per-read count distribution on this proportion of the lowest-ranked densities
	int histoMax = 100;         //Maximum value in our count histogram
	boolean reportPoisson = false; //Fit a ZT Poisson (testing only)
	boolean verbose=false;
	boolean usingPairs=false;
	boolean printNonVerboseHeader=true;
	HashMap<ControlledExperiment, String> infoStrings=new HashMap<ControlledExperiment, String>();
	
	/**
	 * Constructor
	 * @param c
	 * @param man
	 */
	public SeqLibrarySize(GenomeConfig gcon, ExptConfig econ, MultiGPSConfig c){
		gconfig = gcon;
		econfig = econ; 
		config = c;
		gen = config.getGenome();
		econfig.setPerBaseReadFiltering(false);
		usingPairs = econfig.getLoadPairs();
		manager = new ExperimentManager(econfig);
		
		for(ControlledExperiment expt : manager.getReplicates()){
			String name = expt.getSignal().getName().startsWith("experiment") ? expt.getSignal().getSourceName() : expt.getSignal().getName();
			infoStrings.put(expt, new String(name));
		}
	}
	
	//Accessors & settors
	public void setTestQuantile(double tq){
		if(tq>0 && tq<=1.0)
			testQuantile=tq;
		else
			System.err.println("Illegal test quantile: "+tq+", using default: "+testQuantile);
	}
	public void setVerbose(boolean v){verbose=v;}
	public void setPrintHeader(boolean v){printNonVerboseHeader=v;}
	public void setReportPoisson(boolean rp){reportPoisson = rp;}
	public static double getTestQuantile(){return testQuantile;}
	
	/**
	 * Run the library size estimates
	 */
	public void execute(){
		if(!verbose && printNonVerboseHeader)
			printHeader();
		
		//Library size calculation
		Map<ControlledExperiment, Double> meanFragmentCoverage = estimateLibrarySize();
				
		if(!verbose)
			for(ControlledExperiment expt : manager.getReplicates())
				System.out.println(infoStrings.get(expt));
	}
	
	/**
	 * estimateLibrarySize
	 * returns the mean coverage of each fragment
	 */
	public Map<ControlledExperiment,Double> estimateLibrarySize(){
		Map<ControlledExperiment, Double> meanFragmentCoverage = new HashMap<ControlledExperiment, Double>();
		
		//If we have multiple experiments, process one at a time
		for(ControlledExperiment expt : manager.getReplicates()){
			// 1: Find the density of covered bases surrounding each read
			List<DensityCountPair> densities = new ArrayList<DensityCountPair>();
			Iterator<Region> chroms = new ChromosomeGenerator().execute(gen);
			while (chroms.hasNext()) {
				Region currentRegion = chroms.next();
				//Split the job up into large chunks
	            for(int x=currentRegion.getStart(); x<=currentRegion.getEnd(); x+=config.MAXSECTION){
	                int y = x+config.MAXSECTION; 
	                if(y>currentRegion.getEnd()){y=currentRegion.getEnd();}
	                Region currSubRegion = new Region(gen, currentRegion.getChrom(), x, y);
				
	                if(usingPairs && expt.getSignal().getPairCount()>0){
	                	List<StrandedPair> ipPairs = expt.getSignal().getPairs(currSubRegion);
	                	Collections.sort(ipPairs);
	                	makePairR1StartArray(ipPairs, currSubRegion);
						float r1Counts[] = startcounts.clone();
						int halfDWin = (int)densityWindow/2;
						
						//Assuming here that duplicates are already collapsed (should be true in HitCache)
						for(StrandedPair p : ipPairs){
							int i = p.getR1Coordinate()-currSubRegion.getStart();
							int beginIndex = Math.max(p.getR1Coordinate()-halfDWin-currSubRegion.getStart(), 0); 
							int endIndex = Math.min(p.getR1Coordinate()+halfDWin-currSubRegion.getStart(), currSubRegion.getWidth());
							float dens =0; 
							for(int j=beginIndex; j<endIndex; j++){
								if(r1Counts[j]>0 && j!=i)
									dens++;
							}dens /= (endIndex-beginIndex);
							densities.add(new DensityCountPair(dens, p.getWeight()));
						}
	                }else{
						List<StrandedBaseCount> ipHits = expt.getSignal().getBases(currSubRegion);
						makeHitStartArray(ipHits, currSubRegion, '+');
						float posCounts[] = startcounts.clone();
						makeHitStartArray(ipHits, currSubRegion, '-');
						float negCounts[] = startcounts.clone();
			            
						int halfDWin = (int)densityWindow/2;
						for(int index=currSubRegion.getStart()+halfDWin; index<currSubRegion.getEnd()-halfDWin; index++){  //Note that this means a tiny fraction of reads that are located on the large block boundaries will be ignored
							int i = index-currSubRegion.getStart();
							if(posCounts[i]>0){ //Treat fragments on opposite strands separately
								float dens =0; 
								for(int j=i-halfDWin; j<i+halfDWin; j++){
									if(posCounts[j]>0 && j!=i)
										dens++;
									if(negCounts[j]>0)
										dens++;
								}
								dens /= (densityWindow*2);
								densities.add(new DensityCountPair(dens, posCounts[i]));
							}
							if(negCounts[i]>0){ //Treat fragments on opposite strands separately
								float dens =0; 
								for(int j=i-halfDWin; j<i+halfDWin; j++){
									if(posCounts[j]>0)	
										dens++;
									if(negCounts[j]>0 && j!=i)
										dens++;
								}
								dens /= (densityWindow*2);
								densities.add(new DensityCountPair(dens, negCounts[i]));
							}
						}
	                }
	            }
			}
			Collections.sort(densities); //Sort the density pairs in increasing order
			
			//2: Generate a read count per base distribution for the lowest density sites. 
			double currWeight=0, fracWeight=0, quantileLimit=testQuantile*expt.getSignal().getHitCount();
			RealValuedHistogram histo = new RealValuedHistogram(0,histoMax,histoMax);
			RealValuedHistogram fullHisto = new RealValuedHistogram(0,histoMax,histoMax);
			for(DensityCountPair dcp : densities){
				fullHisto.addValue(dcp.getCount());
				if(currWeight<quantileLimit){
					histo.addValue(dcp.getCount());
					fracWeight+=dcp.getCount();
				}
				currWeight+=dcp.getCount();
			}
			
			CensusLibraryComplexity census = new CensusLibraryComplexity(histo, 1, 15);
			census.setVerbose(verbose);
			census.execute();
			
			meanFragmentCoverage.put(expt,  census.getEstimatedNegBinomialMean());
			
			//Get statistics on fragments 
			double pLibrarySize = census.getEstimatedPoissonLibSize() / testQuantile;
			double pMean = census.getEstimatedPoissonLambda();
			double pLibraryCoverage = census.getEstimatedPoissonObservedCoverage();
			double pLibraryCoverageDoubledSeq = census.getEstimatedPoissonObservedGivenCount(2*fracWeight);
			double pNovelFragsUnderDoubleSeq = (pLibraryCoverageDoubledSeq-pLibraryCoverage)*pLibrarySize;
			double nbLibrarySize = census.getEstimatedNegBinomialLibSize() / testQuantile;
			double nbLibraryCoverage = census.getEstimatedNegBinomialObservedCoverage();
			double nbLibraryCoverageDoubledSeq = census.getEstimatedNegBinomialObservedGivenCount(2*fracWeight);
			double nbMean = census.getEstimatedNegBinomialMean();
			double nbVar = census.getEstimatedNegBinomialVariance();
			double nbNovelFragsUnderDoubleSeq = (nbLibraryCoverageDoubledSeq-nbLibraryCoverage)*nbLibrarySize;
			
			
			if(verbose){
				String name = expt.getSignal().getName().startsWith("experiment") ? expt.getSignal().getSourceName() : expt.getSignal().getName();
				if(usingPairs)
					System.out.println("Experiment: "+name+" = "+String.format("%d mapped fragments at %d unique pair-positions", expt.getSignal().getPairCount(),expt.getSignal().getUniquePairCount()));
				else
					System.out.println("Experiment: "+name+" = "+String.format("%.1f mapped tags at %.0f unique positions", expt.getSignal().getHitCount(),expt.getSignal().getHitPositionCount()));
				
				if(reportPoisson){
					System.out.println("\nPer-Fragment Estimated Poisson statistics:");
					System.out.println("Estimated initial mappable library size = "+String.format("%.1f", pLibrarySize) +" fragments");
					System.out.println(String.format("Each fragment was sequenced on average %.5f times", pMean));
					System.out.println(String.format("Proportion of library sequenced: %.3f", pLibraryCoverage));
					System.out.println(String.format("Proportion of library sequenced if you double the number of reads: %.3f (approx %.0f previously unsequenced fragments)", pLibraryCoverageDoubledSeq, pNovelFragsUnderDoubleSeq ));
				}else{
					System.out.println("\nPer-Fragment Estimated Negative Binomial statistics:");
					System.out.println("Estimated initial mappable library size = "+String.format("%.1f", nbLibrarySize) +" fragments");
					System.out.println(String.format("Each fragment was sequenced on average %.5f times (%.5f variance, r = %.5f, p = %.5f, k = %.5f)", nbMean, nbVar, census.getEstimatedNegBinomialRP()[0], census.getEstimatedNegBinomialRP()[1], census.getEstimatedNegBinomialGammaK()));
					System.out.println(String.format("Proportion of library sequenced: %.3f", nbLibraryCoverage));
					System.out.println(String.format("Proportion of library sequenced if you double the number of reads: %.3f (approx %.0f previously unsequenced fragments)", nbLibraryCoverageDoubledSeq, nbNovelFragsUnderDoubleSeq ));						
				}
			}else{
				String currInfo = infoStrings.get(expt);
				if(reportPoisson){
					currInfo = currInfo + String.format("\t%.1f\t%.0f\t%.5f\t%.5f\t%.1f\t%.3f\t%.3f", 
							usingPairs ? (float)expt.getSignal().getPairCount() : expt.getSignal().getHitCount(),
							usingPairs ? (float)expt.getSignal().getUniquePairCount() : expt.getSignal().getHitPositionCount(),
							pMean,
							pMean,
							pLibrarySize,
							pLibraryCoverage,
							pLibraryCoverageDoubledSeq
							);
				}else{
					currInfo = currInfo + String.format("\t%.1f\t%.0f\t%.5f\t%.5f\t%.1f\t%.3f\t%.3f", 
							usingPairs ? (float)expt.getSignal().getPairCount() : expt.getSignal().getHitCount(),
							usingPairs ? (float)expt.getSignal().getUniquePairCount() : expt.getSignal().getHitPositionCount(),
							nbMean,
							nbVar,
							nbLibrarySize,
							nbLibraryCoverage,
							nbLibraryCoverageDoubledSeq
							);
				}
				infoStrings.put(expt, currInfo);
			}
		}
		return meanFragmentCoverage;
	}	
	
	//Makes integer arrays corresponding to the read starts over the current region
	protected void makeHitStartArray(List<StrandedBaseCount> hits, Region currReg, char strand){
		startcounts = new float[currReg.getWidth()+1];
		for(int i=0; i<=currReg.getWidth(); i++){startcounts[i]=0;}
		for(StrandedBaseCount r : hits){
			if(strand=='.' || r.getStrand()==strand){
				int offset=inBounds(r.getCoordinate()-currReg.getStart(),0,currReg.getWidth());
				startcounts[offset]+=r.getCount();
			}
		}
	}
	
	//Makes integer arrays corresponding to the pair R1 starts over the current region (just for denisty purposes)
	protected void makePairR1StartArray(List<StrandedPair> pairs, Region currReg){
		startcounts = new float[currReg.getWidth()+1];
		for(int i=0; i<=currReg.getWidth(); i++){startcounts[i]=0;}
		for(StrandedPair p : pairs){
			int offset=inBounds(p.getR1Coordinate()-currReg.getStart(),0,currReg.getWidth());
			startcounts[offset]+=p.getWeight();
		}
	}
	
	protected final int inBounds(int x, int min, int max){
		if(x<min){return min;}
		if(x>max){return max;}
		return x;
	}
	
	public void close(){
		if(manager==null)
			manager.close();
	}
	
	/**
	 * Main driver method
	 */
	public static void main(String[] args) {
		ArgParser ap = new ArgParser(args);
		if(args.length==0 || ap.hasKey("h")){
			System.err.println("SeqQC:\n" +
					"\t--geninfo <genome info file>\n" +
					"\t--expt <experiment to test QC>\n" +
					"\t--format <format of experiment files (SAM/BED/IDX)>\n" +
					"\t--testquantile <fraction of sparsest density reads from which to build distribution (default="+SeqLibrarySize.getTestQuantile()+")>\n" +
					"\t--ztnb [fit a zero-truncated Negative Binomial (default)]\n" +
					"\t--ztp [fit a zero-truncated Poisson (not recommended - for testing only)]\n" +
					"\t--verbose [print some more information]\n" +
					"\t--noheader [drop the header in non-verbose mode]\n");
		}else{
			GenomeConfig gcon = new GenomeConfig(args);
			ExptConfig econ = new ExptConfig(gcon.getGenome(), args);
			MultiGPSConfig config = new MultiGPSConfig(gcon, args, false);
			SeqLibrarySize sls = new SeqLibrarySize(gcon, econ, config);
			if(ap.hasKey("testquantile"))
				sls.setTestQuantile(new Double(ap.getKeyValue("testquantile")));
			if(ap.hasKey("verbose"))
				sls.setVerbose(true);
			if(ap.hasKey("noheader"))
				sls.setPrintHeader(false);
			if(ap.hasKey("ztp"))
				sls.setReportPoisson(true);
			if(ap.hasKey("ztnb"))
				sls.setReportPoisson(false);
			sls.execute();
			sls.close();
		}
	}
	
	private void printHeader(){
		if(reportPoisson){
			System.out.println("Dataset\t" +
					"MappedTags\t" +
					"MappedPositions\t" +
					"EstZTPMean\t" +
					"EstZTPVar\t" +
					"EstLibSize\t" +
					"EstLibCoverage\t" +
					"EstLibCoverageDoubleSeq\t" +
					"");
		}else{
			System.out.println("Dataset\t" +
					"MappedTags\t" +
					"MappedPositions\t" +
					"EstZTNBMean\t" +
					"EstZTNBVar\t" +
					"EstLibSize\t" +
					"EstLibCoverage\t" +
					"EstLibCoverageDoubleSeq\t" +
					"");
		}
	}
	
	private class DensityCountPair implements Comparable<DensityCountPair>{
		private float density;
		private float count;
		
		public DensityCountPair(float d, float c){
			density = d;
			count =c;
		}
		
		public void setCount(float count) {
			this.count = count;
		}
		public void setDensity(float density) {
			this.density = density;
		}
		public float getCount() {
			return count;
		}
		public float getDensity() {
			return density;
		}
		
		// sort according to density
		public int compareTo(DensityCountPair dc) {
			if(density < dc.density)
				return -1;
			else if (density > dc.density)
				return 1;
			else return 0;
		}

	}
}
