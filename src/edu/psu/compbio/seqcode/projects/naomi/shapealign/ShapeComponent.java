package edu.psu.compbio.seqcode.projects.naomi.shapealign;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import edu.psu.compbio.seqcode.deepseq.experiments.ControlledExperiment;
import edu.psu.compbio.seqcode.deepseq.experiments.ExperimentManager;
import edu.psu.compbio.seqcode.deepseq.experiments.ExptConfig;
import edu.psu.compbio.seqcode.genome.GenomeConfig;
import edu.psu.compbio.seqcode.genome.location.StrandedPoint;
import edu.psu.compbio.seqcode.genome.location.StrandedRegion;
import edu.psu.compbio.seqcode.gse.tools.utils.Args;
import edu.psu.compbio.seqcode.gse.utils.ArgParser;
import edu.psu.compbio.seqcode.gse.utils.Pair;
import edu.psu.compbio.seqcode.gse.utils.io.RegionFileUtilities;
import edu.psu.compbio.seqcode.projects.naomi.sequtils.FeatureCountsLoader;

/**
 * Probabilistic approach to find the best alignment and updated read distributions given a prior (initial read distributions).
 * This models the positive and negative strand separately.
 * @author naomi yamada
 */

public class ShapeComponent {
	
	protected FeatureCountsLoader featureCountsLoader;
	protected ExperimentManager manager;
		
	protected List<StrandedRegion> strandeRegions;
	protected int window;
	
	protected int min, max;		// the start and end position
	protected int summit;		// the position of highest prob point
	protected double[][] data;
	protected double[][] probs;
	protected String fileName;
	protected List<Pair<Integer, double[]>> empiricalDistribution;
	
	
	protected Map<ControlledExperiment, Map<StrandedRegion, double[][]>> strandedRegionSampleCounts = new HashMap<ControlledExperiment, Map<StrandedRegion,double[][]>>();
	
	public ShapeComponent(FeatureCountsLoader fcloader, ExperimentManager man){
		fcloader = featureCountsLoader;
		man = manager;
	}
	
	// setters
	public void setWidth(int w){window = w;}
	
	// BindingModel class from multiGPS is adopted and modified to take two double values instead of one
	public void readShapeModel(File f, int minDist, int maxDist){ 
		min=0; max=0;
		fileName = f.getName();
		try {
			empiricalDistribution = new LinkedList<Pair<Integer,double[]>>(); 
			BufferedReader reader = new BufferedReader(new FileReader(f));
	        String line;
	        while ((line = reader.readLine()) != null) {
	            line = line.trim();
	            String[] words = line.split("\\s+");
	            if(words.length>=2){
	              Integer dist = new Integer(words[0]);
	              double pos = new Double(words[1]);
	              double neg = new Double(words[2]);
	              //make sure the current data point is within the specified range
	              if ((dist.intValue() >= minDist) && (dist.intValue() <= maxDist)) {
	            	double[] counts = new double[2];
	            	counts[0]= pos;
	            	counts[1] = neg;
	                Pair<Integer,double[]> p = new Pair<Integer,double[]>(dist, counts);
	                if (p.cdr()[0] >= 0 && p.cdr()[1] >= 0) // should be non-negative value
	                	empiricalDistribution.add(p);
	                else {
	                	System.err.println("\nRead distribution file contains negative probability(count) value!"); 
	                	System.exit(1);
	                }
	              }
	            }
	        }
	        loadData(empiricalDistribution);
			makeProbabilities();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	//Load data
	protected void loadData(List<Pair<Integer, double[]>> bindingDist){
		//Assumes the list is sorted//
		
		//Find max, min values first
		for(Pair<Integer, double[]> p : bindingDist){
			if(p.car()<min)
				min=p.car();
			if(p.car()>max)
				max=p.car();
		}
		//Initialize arrays
		data = new double[(max-min)+1][2];
		probs = new double[(max-min)+1][2];
		for(int i=0; i<=(max-min); i++){
			for (int s = 0 ; s <2; s++){
				data[i][s]=0; probs[i][s]=0;
			}
		}
		
		//Populate the data array (assumes sorted)
		int last=min-1;
		for(Pair<Integer, double[]> p : bindingDist){
			int index = p.car();
			double[] val = p.cdr();
			//if list is not properly sorted (need to make this into an exception)
			if(index-last<0){
				System.err.println("Incorrectly sorted binding read distribution data!"); 
				System.exit(1);
			}
			data[index-min]=val;
			
			last = p.car();
		}
	}
	
	//Set a probability landscape according to the data. 
	protected void makeProbabilities(){

	}
	
	
	public void loadFeatureCounts(){
		
	}
	
	
	public void runShapeEM(){
		
	}
	
	public static void main(String[] args){
		
		GenomeConfig gconf = new GenomeConfig(args);
		ExptConfig econf = new ExptConfig(gconf.getGenome(), args);		
		econf.setPerBaseReadFiltering(false);		
		ExperimentManager manager = new ExperimentManager(econf);
		
		// parsing command line arguments
		ArgParser ap = new ArgParser(args);		
		int win = Args.parseInteger(args, "win", 200);
		List<StrandedPoint> spoints = RegionFileUtilities.loadStrandedPointsFromMotifFile(gconf.getGenome(), ap.getKeyValue("peaks"), win);
		
		// window size must be twice bigger so it can slide window size times
		FeatureCountsLoader fcLoader = new FeatureCountsLoader(gconf, spoints, win);

		ShapeComponent profile = new ShapeComponent(fcLoader, manager); 	
		profile.setWidth(win);
		profile.runShapeEM();
		
		manager.close();
	}
	
}