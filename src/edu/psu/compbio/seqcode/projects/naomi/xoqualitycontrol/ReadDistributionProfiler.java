package edu.psu.compbio.seqcode.projects.naomi.xoqualitycontrol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.psu.compbio.seqcode.deepseq.StrandedBaseCount;
import edu.psu.compbio.seqcode.deepseq.experiments.ControlledExperiment;
import edu.psu.compbio.seqcode.deepseq.experiments.ExperimentCondition;
import edu.psu.compbio.seqcode.deepseq.experiments.ExperimentManager;
import edu.psu.compbio.seqcode.deepseq.experiments.ExptConfig;
import edu.psu.compbio.seqcode.deepseq.experiments.Sample;
import edu.psu.compbio.seqcode.genome.Genome;
import edu.psu.compbio.seqcode.genome.GenomeConfig;
import edu.psu.compbio.seqcode.genome.location.Point;
import edu.psu.compbio.seqcode.genome.location.Region;
import edu.psu.compbio.seqcode.genome.location.StrandedPoint;
import edu.psu.compbio.seqcode.genome.location.StrandedRegion;
import edu.psu.compbio.seqcode.gse.tools.utils.Args;
import edu.psu.compbio.seqcode.gse.utils.ArgParser;
import edu.psu.compbio.seqcode.gse.utils.io.RegionFileUtilities;

/**
 * ReadDistributionProfiler : calculates weighted standard deviation around the reference point as in Rohit thesis
 * 
 * input : reference point
 * 
 * @author naomi yamada
 */

public class ReadDistributionProfiler {
	
	protected GenomeConfig gconfig;
	protected ExptConfig econfig;
	protected ExperimentManager manager;
		
	protected List<StrandedPoint> strandedPoints;
	protected List<StrandedRegion> strandedRegions;
	
	protected int fivePrimeShift = 0;
	protected int expandSize = 40;
	protected int window = 1000;
	
	Map<Sample,double[]> sampleComposite = new HashMap<Sample,double[]>();
	Map<Sample,double[]> sampleStandardDeviation = new HashMap<Sample,double[]>();
	
	public ReadDistributionProfiler(GenomeConfig gcon, ExptConfig econ, ExperimentManager man){	
		gconfig = gcon;
		econfig = econ;
		manager = man;
	}
	
	// setters
	public void setStrandedPoints(List<StrandedPoint> p){strandedPoints = p;}
	public void setStrandedRegions(List<StrandedRegion> reg){strandedRegions = reg;} 
	public void setWindowSize(int w){window = w;}
	public void setFivePrimeShift(int s){fivePrimeShift = s;}
	
	public void getCountsfromStrandedRegions(){
		
		// StrandedBaseCount list for each stranded regions for each sample
		Map<Sample, Map<StrandedRegion,List<StrandedBaseCount>>> sampleCountsMap = new HashMap<Sample, Map<StrandedRegion,List<StrandedBaseCount>>>();
		
		// sample counts array for each stranded region for each sample
		Map<Sample, Map<StrandedRegion,double[][]>> sampleCountsArray = new HashMap<Sample, Map<StrandedRegion,double[][]>>();
				
		List<StrandedRegion> regionList = new ArrayList<StrandedRegion>();
		for(Point p: strandedPoints){		
			int start = Math.max(1, p.getLocation() - window/2);
			int end = Math.min(p.getLocation() + window/2, p.getGenome().getChromLength(p.getChrom()));				
			StrandedRegion strandedReg = new StrandedRegion(p.getGenome(), p.getChrom(), start, end, p.getStrand());					
			regionList.add(strandedReg);
		}
		setStrandedRegions(regionList);
		
		for (ExperimentCondition condition : manager.getConditions()){		
			for (ControlledExperiment rep: condition.getReplicates()){				
				Map<StrandedRegion,List<StrandedBaseCount>> regionCounts =  new HashMap<StrandedRegion,List<StrandedBaseCount>>();				
				for (StrandedRegion reg : strandedRegions){
					regionCounts.put(reg, rep.getSignal().getBases(reg));
				}
				sampleCountsMap.put(rep.getSignal(),regionCounts);
			}					
		}
		
		//StrandedBasedCount object contains positive and negative strand separately
		// Reverse the array depending of strand of features	
		for (Sample sample : sampleCountsMap.keySet()){
			
			Map<StrandedRegion,double[][]> regionCounts = new HashMap<StrandedRegion,double[][]>();
			
			for (StrandedRegion reg : sampleCountsMap.get(sample).keySet()){			
				double[][] sampleCounts = new double[window+expandSize+1][2];
				for (int i = 0;i <= window+expandSize;i++){
					for (int s = 0; s<2; s++)
						sampleCounts[i][s] = 0;
				}				
				for (StrandedBaseCount hits: sampleCountsMap.get(sample).get(reg)){				
					if (reg.getStrand() == '+'){
						if (hits.getStrand()=='+'){
							sampleCounts[hits.getCoordinate()-reg.getStart()][0] = hits.getCount();
						}else{
							sampleCounts[hits.getCoordinate()-reg.getStart()][1] = hits.getCount();
						}
					}else{ // if the features are forward stranded, array needs to be reversed with reversed strand
						if (hits.getStrand()=='-'){
							sampleCounts[reg.getEnd()-hits.getCoordinate()][1] = hits.getCount();
						}else{
							sampleCounts[reg.getEnd()-hits.getCoordinate()][0] = hits.getCount();						
						}
					}
				}
				regionCounts.put(reg, sampleCounts);
			}
			sampleCountsArray.put(sample, regionCounts);
		}
					
		// nonstrandedComposite is a shifted and strand merged composite to be used to measure standard deviation
		for (Sample sample : sampleCountsArray.keySet()){
			
			double [] nonstrandedComposite = new double[window+1];
			for (int i = 0; i <=window ; i ++)
				nonstrandedComposite[i] = 0;
			
			for (Region reg : sampleCountsArray.get(sample).keySet()){				
				double[][] regionCounts = sampleCountsArray.get(sample).get(reg);				
				// get shifted composite for forward and reverse strands
				for (int j = 0 ; j <=window ; j++){
					nonstrandedComposite[j] += regionCounts[j-fivePrimeShift+expandSize/2][0]; 
					nonstrandedComposite[j] += regionCounts[j+fivePrimeShift+expandSize/2][1];
				}
			}
			sampleComposite.put(sample, nonstrandedComposite);		
		}
		manager.close();
	}
		
	public void computeStandardDeviation(){
			
		// calculate standard deviation
		for (Sample sample : sampleComposite.keySet()){
			
			double[] composite = sampleComposite.get(sample);		
			double sumWeightedVar = 0 ; double sumWeights = 0; double weightedVar = 0; double weightedSD = 0 ;
			double [] distributionScore = new double [2]; 		
			int maxIndex = -1;
			double maxCount = 0;			
			for (int i = 0 ; i <=window; i ++){
				if (composite[i] > maxCount){
					maxCount = composite[i];
					maxIndex = i;
				}
			}			
			for (int i = 0; i <=window; i++){
				sumWeightedVar += composite[i]*(i-maxIndex)*(i-maxIndex);
				sumWeights += composite[i];
			}
			weightedVar = (window*sumWeightedVar)/((window-1)*sumWeights);
			weightedSD = Math.sqrt(weightedVar);				
			distributionScore[0] = window/2 - maxIndex;
			distributionScore[1] = weightedSD;
			
			sampleStandardDeviation.put(sample, distributionScore);
		}		
	}
	
	public void printSampleStandardDeviation(){		
		for (Sample sample : sampleStandardDeviation.keySet()){			
			double [] distributionScore = sampleStandardDeviation.get(sample);			
			System.out.println(sample.getName()+":"+distributionScore[0]+":"+distributionScore[1]);			
		}		
	}	
	
	public void printSampleComposite(){		
		for (Sample sample : sampleComposite.keySet()){			
			double [] composite = sampleComposite.get(sample);
			System.out.println(sample.getName());
			for (int i = 0; i < composite.length; i++){
				System.out.print(composite[i]+"\t");
			}
			System.out.println();			
		}		
	}	
	
	public static void main(String[] args){
		
		GenomeConfig gconf = new GenomeConfig(args);
		ExptConfig econf = new ExptConfig(gconf.getGenome(), args);		
		econf.setPerBaseReadFiltering(false);		
		ExperimentManager manager = new ExperimentManager(econf);
		
		// parsing command line arguments
		ArgParser ap = new ArgParser(args);		
		int win = Args.parseInteger(args, "win", 1000);
		int fivePrimeShift = Args.parseInteger(args,"readshift", 6);
		List<StrandedPoint> spoints = RegionFileUtilities.loadStrandedPointsFromMotifFile(gconf.getGenome(), ap.getKeyValue("peaks"), win);

		ReadDistributionProfiler profile = new ReadDistributionProfiler(gconf, econf, manager); 	
		profile.setStrandedPoints(spoints);
		profile.setWindowSize(win);
		profile.setFivePrimeShift(fivePrimeShift);
		profile.getCountsfromStrandedRegions();
		profile.computeStandardDeviation();
		profile.printSampleStandardDeviation();		
		if (Args.parseFlags(args).contains("printComposite")){profile.printSampleComposite();}
		
		manager.close();
	}
}