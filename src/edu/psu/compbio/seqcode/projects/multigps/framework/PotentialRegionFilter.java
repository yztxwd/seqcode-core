package edu.psu.compbio.seqcode.projects.multigps.framework;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import edu.psu.compbio.seqcode.deepseq.StrandedBaseCount;
import edu.psu.compbio.seqcode.deepseq.experiments.ControlledExperiment;
import edu.psu.compbio.seqcode.deepseq.experiments.ExperimentCondition;
import edu.psu.compbio.seqcode.deepseq.experiments.ExperimentManager;
import edu.psu.compbio.seqcode.deepseq.experiments.ExptConfig;
import edu.psu.compbio.seqcode.deepseq.experiments.Sample;
import edu.psu.compbio.seqcode.deepseq.stats.BackgroundCollection;
import edu.psu.compbio.seqcode.deepseq.stats.PoissonBackgroundModel;
import edu.psu.compbio.seqcode.genome.Genome;
import edu.psu.compbio.seqcode.genome.GenomeConfig;
import edu.psu.compbio.seqcode.genome.location.Region;
import edu.psu.compbio.seqcode.gse.gsebricks.verbs.location.ChromosomeGenerator;
import edu.psu.compbio.seqcode.gse.utils.RealValuedHistogram;

/**
 * PotentialRegionFilter: Find a set of regions that are above a threshold in at least one condition. 
 * 		A region the size of the model span (i.e. 2x model range) potentially contains a binding site if 
 * 		it passes all Poisson thresholds in at least one replicate from one condition.
 * 		The Poisson thresholds are based on the model span size to keep consistent with the final used thresholds. 
 * Overall counts for reads in potential regions and outside potential regions are maintained to assist noise model initialization.  
 * 
 * @author Shaun Mahony
 * @version	%I%, %G%
 */
public class PotentialRegionFilter {

	protected ExperimentManager manager; 
	protected BindingManager bindingManager;
	protected MultiGPSConfig config;
	protected ExptConfig econfig;
	protected Genome gen;
	protected float maxBinWidth=0, binStep, winExt;
	protected boolean loadControl=true; 
	protected boolean stranded=false;
	protected List<Region> potentialRegions = new ArrayList<Region>();
	protected double potRegionLengthTotal=0;
	protected HashMap<ExperimentCondition, BackgroundCollection> conditionBackgrounds=new HashMap<ExperimentCondition, BackgroundCollection>(); //Background models for each replicate
	protected HashMap<ExperimentCondition, Double> potRegCountsSigChannel = new HashMap<ExperimentCondition, Double>();
	protected HashMap<ExperimentCondition, Double> nonPotRegCountsSigChannel = new HashMap<ExperimentCondition, Double>();
	protected HashMap<ExperimentCondition, Double> potRegCountsCtrlChannel = new HashMap<ExperimentCondition, Double>();
	protected HashMap<ExperimentCondition, Double> nonPotRegCountsCtrlChannel = new HashMap<ExperimentCondition, Double>();	
	
	public PotentialRegionFilter(MultiGPSConfig c, ExptConfig econ, ExperimentManager eman, BindingManager bman){
		manager = eman;
		bindingManager = bman;
		config = c; 
		econfig = econ;
		gen = config.getGenome();
		//Initialize background models
		for(ExperimentCondition cond : manager.getConditions()){
			conditionBackgrounds.put(cond, new BackgroundCollection());
			int maxIR = 0; boolean hasControls=true; 
			for(ControlledExperiment rep : cond.getReplicates()){
				if(bindingManager.getBindingModel(rep).getInfluenceRange()>maxIR)
					maxIR = bindingManager.getBindingModel(rep).getInfluenceRange();
				hasControls = hasControls && rep.hasControl();
			}
			
			float binWidth = maxIR;
    		if(binWidth>maxBinWidth){maxBinWidth=binWidth;}
    			
    		//global threshold
    		conditionBackgrounds.get(cond).addBackgroundModel(new PoissonBackgroundModel(-1, config.getPRLogConf(), cond.getTotalSignalCount(), config.getGenome().getGenomeLength(), econfig.getMappableGenomeProp(), binWidth, '.', 1, true));
    		for(Integer i : econfig.getLocalBackgroundWindows()){
    			if(hasControls){//local control thresholds 
    				//signal threshold based on what would be expected from the CONTROL locality
    				conditionBackgrounds.get(cond).addBackgroundModel(new PoissonBackgroundModel(i.intValue(), config.getPRLogConf(), cond.getTotalControlCount(),  config.getGenome().getGenomeLength(), econfig.getMappableGenomeProp(), binWidth, '.', 1, false));
                }else{
                	//local signal threshold -- this may bias against locally enriched signal regions, and so should only be used if there is no control or if the control is not yet scaled
                	if(i.intValue()>=10000) // we don't want the window too small in this case
                		conditionBackgrounds.get(cond).addBackgroundModel(new PoissonBackgroundModel(i.intValue(), config.getPRLogConf(), cond.getTotalSignalCount(), config.getGenome().getGenomeLength(), econfig.getMappableGenomeProp(), binWidth, '.', 1, true));
                }	
    		}
    			
    		System.err.println("PotentialRegionFilter: genomic threshold for "+cond.getName()+" with bin width "+binWidth+" = "+conditionBackgrounds.get(cond).getGenomicModelThreshold());
    			
    		//Initialize counts
    		potRegCountsSigChannel.put(cond, 0.0);
    		nonPotRegCountsSigChannel.put(cond, 0.0);
    		potRegCountsCtrlChannel.put(cond, 0.0);
    		nonPotRegCountsCtrlChannel.put(cond, 0.0);
    	}
		binStep = config.POTREG_BIN_STEP;
		if(binStep>maxBinWidth/2)
			binStep=maxBinWidth/2;
		winExt = maxBinWidth/2;
	}
	
	//Accessors for read counts
	public Double getPotRegCountsSigChannel(ExperimentCondition e){ return potRegCountsSigChannel.get(e);}
	public Double getNonPotRegCountsSigChannel(ExperimentCondition e){ return nonPotRegCountsSigChannel.get(e);}
	public Double getPotRegCountsCtrlChannel(ExperimentCondition e){ return potRegCountsCtrlChannel.get(e);}
	public Double getNonPotRegCountsCtrlChannel(ExperimentCondition e){ return nonPotRegCountsCtrlChannel.get(e);}
	public List<Region> getPotentialRegions(){return potentialRegions;}
	public double getPotRegionLengthTotal(){return potRegionLengthTotal;}
	
	/**
	 * Find list of potentially enriched regions 
	 * (windows that contain the minimum number of reads needed to pass the Poisson backgrounds).
	 * @param testRegions
	 */
	public List<Region> execute(){
		//TODO: check config for defined subset of regions
		Iterator<Region> testRegions = new ChromosomeGenerator().execute(config.getGenome());
		
		//Threading divides analysis over entire chromosomes. This approach is not compatible with file caching. 
		int numThreads = econfig.getCacheAllData() ? config.getMaxThreads() : 1;
				
		Thread[] threads = new Thread[numThreads];
        ArrayList<Region> threadRegions[] = new ArrayList[numThreads];
        int i = 0;
        for (i = 0 ; i < threads.length; i++) {
            threadRegions[i] = new ArrayList<Region>();
        }
        while(testRegions.hasNext()){
        	Region r = testRegions.next(); 
            threadRegions[(i++) % numThreads].add(r);
        }

        for (i = 0 ; i < threads.length; i++) {
            Thread t = new Thread(new PotentialRegionFinderThread(threadRegions[i]));
            t.start();
            threads[i] = t;
        }
        boolean anyrunning = true;
        while (anyrunning) {
            anyrunning = false;
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) { }
            for (i = 0; i < threads.length; i++) {
                if (threads[i].isAlive()) {
                    anyrunning = true;
                    break;
                }
            }
        }
                
        for(Region r : potentialRegions)
        	potRegionLengthTotal+=(double)r.getWidth();
        
     	return potentialRegions;
	}
	
	/**
     * Print potential regions to a file.
     * TESTING ONLY 
     */
    public void printPotentialRegionsToFile(){
    	try {
    		String filename = config.getOutputIntermediateDir()+File.separator+config.getOutBase()+".potential.regions";
			FileWriter fout = new FileWriter(filename);
			for(Region r : potentialRegions){
	    		fout.write(r.getLocationString()+"\n");			
	    	}
			fout.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
    }
	
    class PotentialRegionFinderThread implements Runnable {
        private Collection<Region> regions;
        private double[][] landscape=null;
        private double[][] starts=null;
        private List<Region> threadPotentials = new ArrayList<Region>();
        
        public PotentialRegionFinderThread(Collection<Region> r) {
            regions = r;
        }
        
        public void run() {
        	int expansion = (int)(winExt + maxBinWidth/2);
        	for (Region currentRegion : regions) {
            	Region lastPotential=null;
                //Split the job up into large chunks
                for(int x=currentRegion.getStart(); x<=currentRegion.getEnd(); x+=config.MAXSECTION){
                    int y = (int) (x+config.MAXSECTION+(expansion)); //Leave a little overhang to handle enriched regions that may hit the border. Since lastPotential is defined above, a region on the boundary should get merged in.
                    if(y>currentRegion.getEnd()){y=currentRegion.getEnd();}
                    Region currSubRegion = new Region(gen, currentRegion.getChrom(), x, y);
                    
                    List<Region> currPotRegions = new ArrayList<Region>();
                    List<List<StrandedBaseCount>> ipHits = new ArrayList<List<StrandedBaseCount>>();
                    List<List<StrandedBaseCount>> backHits = new ArrayList<List<StrandedBaseCount>>();
                    
                    synchronized(manager){
	                    //Initialize the read lists
                    	for(ExperimentCondition cond : manager.getConditions()){
                    		ipHits.add(new ArrayList<StrandedBaseCount>());
                    		backHits.add(new ArrayList<StrandedBaseCount>());
                    	}
                    	//Load reads by condition
                    	for(ExperimentCondition cond : manager.getConditions()){
                    		for(ControlledExperiment rep : cond.getReplicates())
                    			ipHits.get(cond.getIndex()).addAll(rep.getSignal().getBases(currSubRegion));
                    		for(Sample ctrl : cond.getControlSamples())
                    			backHits.get(cond.getIndex()).addAll(ctrl.getBases(currSubRegion));
                    	}
                    }
            		int numStrandIter = stranded ? 2 : 1;
                    for(int stranditer=1; stranditer<=numStrandIter; stranditer++){
                        //If stranded peak-finding, run over both strands separately
                        char str = !stranded ? '.' : (stranditer==1 ? '+' : '-');
					 
                        makeHitLandscape(ipHits, currSubRegion, maxBinWidth, binStep, str);
                        double ipHitCounts[][] = landscape.clone();
                        double ipBinnedStarts[][] = starts.clone();
                        double backBinnedStarts[][] = null;
                        if (loadControl) {
                            makeHitLandscape(backHits, currSubRegion, maxBinWidth, binStep, str);
                            backBinnedStarts = starts.clone();
                        }
					
                        //Scan regions
                        int currBin=0;
                        for(int i=currSubRegion.getStart(); i<currSubRegion.getEnd()-(int)maxBinWidth; i+=(int)binStep){
                        	boolean regionPasses=false;
                        	for(ExperimentCondition cond : manager.getConditions()){
                        		double ipWinHits=ipHitCounts[cond.getIndex()][currBin];
                        		//First Test: is the read count above the genome-wide thresholds? 
                        		if(conditionBackgrounds.get(cond).passesGenomicThreshold((int)ipWinHits, str)){
                        			//Second Test: refresh all thresholds & test again
                        			conditionBackgrounds.get(cond).updateModels(currSubRegion, i-x, ipBinnedStarts[cond.getIndex()], backBinnedStarts==null ? null : backBinnedStarts[cond.getIndex()], binStep);
                        			if(conditionBackgrounds.get(cond).passesAllThresholds((int)ipWinHits, str)){
                        				//If the region passes the thresholds for one condition, it's a potential
                        				regionPasses=true;
                        				break;
		                            }
		                        }
                        	}
                        	if(regionPasses){
                        		Region currPotential = new Region(gen, currentRegion.getChrom(), Math.max(i-expansion, 1), Math.min((int)(i-1+expansion), currentRegion.getEnd()));
                        		if(lastPotential!=null && currPotential.overlaps(lastPotential)){
                        			lastPotential = lastPotential.expand(0, currPotential.getEnd()-lastPotential.getEnd());
                        		}else{
                        			//Add the last recorded region to the list
                        			if(lastPotential!=null){
                        				if(lastPotential.getWidth()<=config.getBMAnalysisWindowMax()){
                        					currPotRegions.add(lastPotential);
                        					threadPotentials.add(lastPotential);
                        				}else{
                        					//Break up long windows
                        					List<Region> parts = breakWindow(lastPotential, ipHits, config.getBMAnalysisWindowMax(), str);
                        					for(Region p : parts){
                        						currPotRegions.add(p);
                        						threadPotentials.add(p);
                        					}
                        				}
                        			}lastPotential = currPotential;
                        		}
                        	}
                            currBin++;
                        }
					}
                    //Count all "signal" reads overlapping the regions in currPotRegions (including the lastPotential)
                    if(lastPotential!=null)
                    	currPotRegions.add(lastPotential);
                    currPotRegions = filterExcluded(currPotRegions);
                    countReadsInRegions(currPotRegions, ipHits, backHits, y==currentRegion.getEnd() ? y : y-expansion);
                    //Note: it looks like currPotRegions and threadPotentials are redundant in the above, but they are not.
                    //currPotRegions is only used to count sig/noise reads in the current section. threadPotentials stores regions over the entire run.
                }
                //Add the final recorded region to the list
                if(lastPotential!=null)
    				threadPotentials.add(lastPotential);
                threadPotentials = filterExcluded(threadPotentials);
            }
        	if(threadPotentials.size()>0){
        		synchronized(potentialRegions){
        			potentialRegions.addAll(threadPotentials);
        		}
        	}	
        }
        
        //Break up a long window into parts
        //For now, we just choose the break points as the bins with the lowest total signal read count around the desired length.
        //TODO: improve?
        protected List<Region> breakWindow(Region lastPotential, List<List<StrandedBaseCount>> ipHits, int preferredWinLen, char str) {
			List<Region> parts = new ArrayList<Region>();
			makeHitLandscape(ipHits, lastPotential, maxBinWidth, binStep, str);
            double ipHitCounts[][] = landscape.clone();
            
            int currPartStart = lastPotential.getStart();
            double currPartTotalMin=Double.MAX_VALUE; int currPartTotalMinPos = -1;
            int currBin=0;
            for(int i=lastPotential.getStart(); i<lastPotential.getEnd()-(int)maxBinWidth; i+=(int)binStep){
            	if(lastPotential.getEnd()-currPartStart < (preferredWinLen*1.5))
            		break;
            	int currBinTotal=0;
            	for(ExperimentCondition cond : manager.getConditions())
                	currBinTotal+=ipHitCounts[cond.getIndex()][currBin];
            	
            	if(i>(currPartStart+preferredWinLen-1000) && i<(currPartStart+preferredWinLen+1000)){ 
            		if(currBinTotal<currPartTotalMin){
            			currPartTotalMin=currBinTotal;
            			currPartTotalMinPos=i;
            		}
            	}
            	//Add a new part
            	if(i>=(currPartStart+preferredWinLen+1000)){
            		parts.add(new Region(lastPotential.getGenome(), lastPotential.getChrom(), currPartStart, currPartTotalMinPos));
            		currPartStart = currPartTotalMinPos+1;
            		currPartTotalMin=Double.MAX_VALUE; currPartTotalMinPos = -1;
            	}
            	currBin++;
            }
            parts.add(new Region(lastPotential.getGenome(), lastPotential.getChrom(), currPartStart, lastPotential.getEnd()));
            
			return parts;
		}

		//Filter out pre-defined regions to ignore (e.g. tower regions)
        protected List<Region> filterExcluded(List<Region> testRegions) {
			List<Region> filtered = new ArrayList<Region>();
			if(config.getRegionsToIgnore().size()==0)
				return testRegions;
			
			for(Region t : testRegions){
				boolean ignore = false;
				for(Region i : config.getRegionsToIgnore()){
					if(t.overlaps(i)){
						ignore = true; break;
					}
				}
				if(!ignore)
					filtered.add(t);
			}
			return filtered;
		}

		//Makes integer arrays corresponding to the read landscape over the current region.
        //Reads are semi-extended out to bin width
        //No needlefiltering here as that is taken care of during read loading (i.e. in Sample)
    	protected void makeHitLandscape(List<List<StrandedBaseCount>> hits, Region currReg, float binWidth, float binStep, char strand){
    		int numBins = (int)(currReg.getWidth()/binStep);
    		landscape = new double[hits.size()][numBins+1];
    		starts = new double[hits.size()][numBins+1];
    		float halfWidth = binWidth/2;

    		for(ExperimentCondition cond : manager.getConditions()){
        		List<StrandedBaseCount> currHits = hits.get(cond.getIndex());
    			for(int i=0; i<=numBins; i++){landscape[cond.getIndex()][i]=0; starts[cond.getIndex()][i]=0; }
	    		for(StrandedBaseCount r : currHits){
	    			if(strand=='.' || r.getStrand()==strand){
	    				int offset=inBounds(r.getCoordinate()-currReg.getStart(),0,currReg.getWidth());
	    				int binoff = inBounds((int)(offset/binStep), 0, numBins);
	    				starts[cond.getIndex()][binoff]+=r.getCount();
	    				int binstart = inBounds((int)((double)(offset-halfWidth)/binStep), 0, numBins);
	    				int binend = inBounds((int)((double)(offset+halfWidth)/binStep), 0, numBins);
	    				for(int b=binstart; b<=binend; b++)
	    					landscape[cond.getIndex()][b]+=r.getCount();
	    			}
            	}
    		}
    	}
    	protected final int inBounds(int x, int min, int max){
    		if(x<min){return min;}
    		if(x>max){return max;}
    		return x;
    	}
    	
    	/**
    	 * Count the total reads within potential regions via semi binary search.
    	 * Assumes both regs and ipHits are sorted.
    	 * We don't have to check chr String matches, as the hits were extracted from the chromosome
    	 * EndCoord accounts for the extra overhang added to some wide regions
    	 * We also ignore strandedness here -- the object is to count ALL reads that will be loaded for analysis later
    	 * (and that thus will not be accounted for by the global noise model)  
    	 * @param regs
    	 * @param ipHits
    	 * @param ctrlHits
    	 * @param endCoord
    	 */
    	protected void countReadsInRegions(List<Region> regs, List<List<StrandedBaseCount>> ipHits, List<List<StrandedBaseCount>> ctrlHits, int endCoord){
    		//Iterate through experiments
    		for(ExperimentCondition cond : manager.getConditions()){
    			double currPotWeightSig=0, currNonPotWeightSig=0, currPotWeightCtrl=0, currNonPotWeightCtrl=0;
    			//Iterate through signal hits
    			for(StrandedBaseCount hit : ipHits.get(cond.getIndex())){
    				if(regs.size()==0)
    					currNonPotWeightSig+=hit.getCount();
    				else{
    					//Binary search for closest region start
        				int hpoint = hit.getCoordinate();
        				if(hpoint<endCoord){ //Throw this check in for the overhang
	        				int l = 0, r = regs.size()-1;
	        	            while (r - l > 1) {
	        	                int c = (l + r) / 2;
	        	                if (hpoint >= regs.get(c).getStart()) {
	        	                    l = c;
	        	                } else {
	        	                    r = c;
	        	                }
	        	            }
	        	            boolean inPot = false;
	        	            for(int x=l; x<=r; x++){
	        	            	if(hpoint >= regs.get(x).getStart() && hpoint <= regs.get(x).getEnd()){
	        	            		currPotWeightSig+=hit.getCount(); inPot=true; break;
	        	            	}
	        	            }
	        	            if(!inPot)
	        	            	currNonPotWeightSig+=hit.getCount();
        				}
    				}
    			}
    			//Iterate through control hits
    			for(StrandedBaseCount hit : ctrlHits.get(cond.getIndex())){
    				if(regs.size()==0)
    					currNonPotWeightCtrl+=hit.getCount();
    				else{
        				//Binary search for closest region start
        				int hpoint = hit.getCoordinate();
        				if(hpoint<endCoord){ //Throw this check in for the overhang
	        				int l = 0, r = regs.size()-1;
	        	            while (r - l > 1) {
	        	                int c = (l + r) / 2;
	        	                if (hpoint >= regs.get(c).getStart()) {
	        	                    l = c;
	        	                } else {
	        	                    r = c;
	        	                }
	        	            }
	        	            boolean inPot = false;
	        	            for(int x=l; x<=r; x++){
	        	            	if(hpoint >= regs.get(x).getStart() && hpoint <= regs.get(x).getEnd()){
	        	            		currPotWeightCtrl+=hit.getCount(); inPot=true; break;
	        	            	}
	        	            }
	        	            if(!inPot)
	        	            	currNonPotWeightCtrl+=hit.getCount();
        				}
    				}
    			}
    			synchronized(potRegCountsSigChannel){
    				potRegCountsSigChannel.put(cond, potRegCountsSigChannel.get(cond)+currPotWeightSig);
    			}
    			synchronized(nonPotRegCountsSigChannel){
    				nonPotRegCountsSigChannel.put(cond, nonPotRegCountsSigChannel.get(cond)+currNonPotWeightSig);
    			}
    			synchronized(potRegCountsCtrlChannel){
    				potRegCountsCtrlChannel.put(cond, potRegCountsCtrlChannel.get(cond)+currPotWeightCtrl);
    			}
    			synchronized(nonPotRegCountsCtrlChannel){
    				nonPotRegCountsCtrlChannel.put(cond, nonPotRegCountsCtrlChannel.get(cond)+currNonPotWeightCtrl);
    			}
    		}
	    }
    }
    
    
    /**
	 * This main method is only for testing the PotentialRegionFilter
	 * @param args
	 */
	public static void main(String[] args){
		GenomeConfig gconfig = new GenomeConfig(args);
		ExptConfig econfig = new ExptConfig(gconfig.getGenome(), args);
		MultiGPSConfig config = new MultiGPSConfig(gconfig, args);
		if(config.helpWanted()){
			System.err.println("PotentialRegionFilter:");
			System.err.println(config.getArgsList());
		}else{
			ExperimentManager manager = new ExperimentManager(econfig);
			BindingManager bman = new BindingManager(manager);
			RealValuedHistogram histo = new RealValuedHistogram(0, 10000, 20);
			
			System.err.println("Conditions:\t"+manager.getConditions().size());
			for(ExperimentCondition c : manager.getConditions()){
				System.err.println("Condition "+c.getName()+":\t#Replicates:\t"+c.getReplicates().size());
			}
			for(ExperimentCondition c : manager.getConditions()){
				for(ControlledExperiment r : c.getReplicates()){
					System.err.println("Condition "+c.getName()+":\tRep "+r.getName());
					if(r.getControl()==null)
						System.err.println("\tSignal:\t"+r.getSignal().getHitCount());
					else
						System.err.println("\tSignal:\t"+r.getSignal().getHitCount()+"\tControl:\t"+r.getControl().getHitCount());
				}
			}
			
			PotentialRegionFilter filter = new PotentialRegionFilter(config, econfig, manager, bman);
			List<Region> potentials = filter.execute();
			
			int min = Integer.MAX_VALUE;
			int max = -Integer.MAX_VALUE;
			for(Region r : potentials){
				System.out.println(r.getLocationString()+"\t"+r.getWidth());
				histo.addValue(r.getWidth());
				if(r.getWidth()<min)
					min = r.getWidth();
				if(r.getWidth()>max)
					max = r.getWidth();
			}
			System.out.println("Potential Regions: "+potentials.size());
			System.out.println("Min width: "+min+"\tMax width: "+max);
			histo.printContents();
			
			manager.close();
		}
	}
}
