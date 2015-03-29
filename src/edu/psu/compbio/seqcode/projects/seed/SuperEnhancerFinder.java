package edu.psu.compbio.seqcode.projects.seed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;


import edu.psu.compbio.seqcode.deepseq.StrandedBaseCount;
import edu.psu.compbio.seqcode.deepseq.experiments.ExperimentCondition;
import edu.psu.compbio.seqcode.deepseq.experiments.ExperimentManager;
import edu.psu.compbio.seqcode.deepseq.experiments.ExptConfig;
import edu.psu.compbio.seqcode.deepseq.experiments.Sample;
import edu.psu.compbio.seqcode.genome.GenomeConfig;
import edu.psu.compbio.seqcode.genome.location.Region;
import edu.psu.compbio.seqcode.genome.location.StrandedPoint;
import edu.psu.compbio.seqcode.projects.seed.FeatureDetection.FeatureDetectionThread;
import edu.psu.compbio.seqcode.projects.seed.features.EnrichedFeature;
import edu.psu.compbio.seqcode.projects.seed.features.Feature;
import edu.psu.compbio.seqcode.projects.seed.features.SuperEnrichedFeature;

/**
 * 
 * @author akshaykakumanu
 *
 */
public class SuperEnhancerFinder extends DomainFinder{
	
	// pseudocount to calculate fold-change while finding inflectioin point for super enhancers
	public final int PSEUDOCOUINT  = 1;
	
	// At the moment only one TSS is alowed to overlap identified super enahncers
	public final int NO_OVERLAPPING_TSS_ALLOWED = 1;
	
	
	// Min distance from known TSSs for a feature to be called distal
	protected int minDistalDistance;
	
	// Super enhancer stitch distance
	protected int superStitchWin;
	
	// Reference TSSs loaded from strandedpoints peaks file
	protected List<StrandedPoint> refTSSs;
	
	public static String version = "0.1";
	
	/**
	 * Return the class name
	 */
	public String getProgramName(){
		return "edu.psu.compbio.seqcode.projects.seed.SuperEnhancerFinder";
	}
	
	/**
	 * Return a thread for this implementation
	 */
	public FeatureDetectionThread getMyThread(List<Region> regs){
		return new SuperEnhancerFinderThread(regs);
	}

	public SuperEnhancerFinder(GenomeConfig gcon, ExptConfig econ,
			SEEDConfig scon, ExperimentManager man) {
		super(gcon, econ, scon, man);
		// TODO Auto-generated constructor stub
		
		minDistalDistance = sconfig.getMinDistalDistance();
		superStitchWin = sconfig.getSuperStitchWin();
		refTSSs = sconfig.getRefTSSs();
	}
	
	
	public Map<ExperimentCondition, List<Feature>> postProcess() {
		System.err.println("\nSuper Enhancer finding complete.");
		
		//All Enhancers
		this.printEventsFile(features, ".all.domains");

		return features;
	}
	
	public static void main(String[] args){
		System.setProperty("java.awt.headless", "true");
		System.err.println("SuperEnhancerFinder version "+SuperEnhancerFinder.version+"\n\n");
		
		GenomeConfig gcon = new GenomeConfig(args);
		ExptConfig econ = new ExptConfig(gcon.getGenome(), args);
		SEEDConfig scon = new SEEDConfig(gcon, args);
		
		if(scon.helpWanted()){
			//System.out.println(DomainFinder.getDomainFinderArgs());
			System.err.println(gcon.getArgsList()+
					econ.getArgsList()+
					scon.getArgsList());
		}else{
			ExperimentManager man = new ExperimentManager(econ);
			SuperEnhancerFinder finder = new SuperEnhancerFinder(gcon, econ, scon, man);
			System.err.println("\nBeginning Super Enhancer finding...");
			finder.execute();
			man.close();
			
		}
	}
	
	
	
	/**
	 * SuperEnhancetFindetThread that searches for super enhancers
	 * @author akshaykakumanu
	 *
	 */
	public class SuperEnhancerFinderThread extends DomainFinderThread{

		public SuperEnhancerFinderThread(List<Region> regs) {
			super(regs);
			// TODO Auto-generated constructor stub
		}
		
		
		/**
		 * findFeatures: find potentially enriched domains on the genome by comparing tag counts to a background model,
		 * and then finds super enhancers as described in ...et.al
		 */
		public Map<ExperimentCondition, List<Feature>> findFeatures(Region subRegion){
			// Get the enriched domains using the functionality from the superclass (DomainFinderThread)
			// and the over-ridden processDomain methods below 
			return super.findFeatures(subRegion);
		}
		
		
		/**
		 * 
		 */
		protected Map<ExperimentCondition, List<EnrichedFeature>> processDomains(Map<ExperimentCondition,List<EnrichedFeature>> currFeatures, Region currSubRegion){
			Map<ExperimentCondition, List<EnrichedFeature>> superEnhancers = new HashMap<ExperimentCondition, List<EnrichedFeature>>();
			for (ExperimentCondition ec : manager.getConditions()){
				superEnhancers.put(ec, new ArrayList<EnrichedFeature>());
			}
			
			// Removing all enriched domains close to promoters
			for (ExperimentCondition ec : manager.getConditions()){
				keepOnlyDistalFeature(currFeatures.get(ec));
			}
			
			// Remove black-list regions if provided 
			
			for (ExperimentCondition ec : manager.getConditions()){
				for(Region blackList : sconfig.getRegionsToIgnore()){
					Iterator<EnrichedFeature> it = currFeatures.get(ec).iterator();
					while(it.hasNext()){
						EnrichedFeature ef = it.next();
						if(ef.getCoords().overlaps(blackList)){
							it.remove();
						}
					}
				}
			}
			
			// Remove enriched domains less that 100bp. Playing around with the parameter at the moment (Needed especially when extending reads)
			
			for (ExperimentCondition ec : manager.getConditions()){
				Iterator<EnrichedFeature> it = currFeatures.get(ec).iterator();
				while(it.hasNext()){
					EnrichedFeature ef = it.next();
					if(ef.getCoords().getWidth() < 100){
						it.remove();
					}
				}
			}
			
			
			// Do a bunch of more operations of the enriched features
			for(ExperimentCondition ec :  manager.getConditions()){
				// Stitching typical enhancers into super enhancers
				List<SuperEnrichedFeature> stitchedTPEs = this.stichTypicalEnhancers(currFeatures.get(ec));
				
				// Removing SEs spanning more than 1 TSSs
				removeMultiGeneSpanningSEs(stitchedTPEs);
				
				// Quantify the features 
				for(SuperEnrichedFeature sfe : stitchedTPEs){
					Map<Sample, List<StrandedBaseCount>> sfeHitsPos = overlappingHits(hitsPos, sfe);
					Map<Sample, List<StrandedBaseCount>> sfeHitsNeg = overlappingHits(hitsNeg, sfe);
					
					//Trim the coordinates
					trimFeature(sfe, sfeHitsPos, sfeHitsNeg, ec);
					
					//Quantify the feature in each Sample and in the condition in which it was found
					quantifyFeature(sfe,sfeHitsPos,sfeHitsNeg,ec);
				}
				
				superEnhancers.get(ec).addAll(stitchedTPEs);
			}
			return superEnhancers;
		}
		
		/*
		 * Keep only those enriched domains that are distal to known TSSs
		 */
		protected void keepOnlyDistalFeature(List<EnrichedFeature> enrichedFeatures){
			Map<String, List<StrandedPoint>> refTSSsbyChrom = new HashMap<String, List<StrandedPoint>>();
			for(StrandedPoint sp: refTSSs){
				if(refTSSsbyChrom.containsKey(sp.getChrom())){refTSSsbyChrom.get(sp.getChrom()).add(sp);}
				else{refTSSsbyChrom.put(sp.getChrom(),new ArrayList<StrandedPoint>()); refTSSsbyChrom.get(sp.getChrom()).add(sp);}
			}
			Iterator<EnrichedFeature> it = enrichedFeatures.iterator();
			while(it.hasNext()){
				EnrichedFeature ef = it.next();
				int minDistance = Integer.MAX_VALUE;
				if(refTSSsbyChrom.containsKey(ef.getCoords().getChrom())){
					for(StrandedPoint sp : refTSSsbyChrom.get(ef.getCoords().getChrom())){
						int dis = Math.min(sp.distance(ef.getCoords().startPoint()),sp.distance(ef.getCoords().endPoint()));
						if(dis < minDistance){
							minDistance = dis;
						}
					}
				}
				if(minDistance <= minDistalDistance ){
					it.remove();
				}
			}
		}
		
		protected List<SuperEnrichedFeature> stichTypicalEnhancers(List<EnrichedFeature> enhancers){
			List<SuperEnrichedFeature> superEnhancers = new ArrayList<SuperEnrichedFeature>();
			SuperEnrichedFeature lastadded = null;
			try {
				for(EnrichedFeature ef : enhancers){
					if(lastadded == null){
						superEnhancers.add(new SuperEnrichedFeature(ef));
						lastadded = superEnhancers.get(superEnhancers.size()-1);
					}else{
						if(lastadded.getLastTEF().getCoords().getMidpoint().distance(ef.getCoords().getMidpoint()) < superStitchWin){
							superEnhancers.get(superEnhancers.size()-1).addTypicalFeature(ef);
							lastadded = superEnhancers.get(superEnhancers.size()-1);
						}else{
							superEnhancers.add(new SuperEnrichedFeature(ef));
							lastadded = superEnhancers.get(superEnhancers.size()-1);
						}
					}
				}
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return superEnhancers;
		}
		
		protected void removeMultiGeneSpanningSEs(List<SuperEnrichedFeature> SEs){
			Map<String, List<StrandedPoint>> refTSSsbyChrom = new HashMap<String, List<StrandedPoint>>();
			for(StrandedPoint sp: refTSSs){
				if(refTSSsbyChrom.containsKey(sp.getChrom())){refTSSsbyChrom.get(sp.getChrom()).add(sp);}
				else{refTSSsbyChrom.put(sp.getChrom(),new ArrayList<StrandedPoint>()); refTSSsbyChrom.get(sp.getChrom()).add(sp);}
			}
			Iterator<SuperEnrichedFeature> it = SEs.iterator();
			while(it.hasNext()){
				int numTSSs = 0;
				SuperEnrichedFeature sef = it.next();
				if(refTSSsbyChrom.containsKey(sef.getCoords().getChrom())){
					for(StrandedPoint sp :  refTSSs){
						if(sef.getCoords().contains(sp)){
							numTSSs++;
						}
					}
				}
				if(numTSSs > NO_OVERLAPPING_TSS_ALLOWED){
					it.remove();
				}
			}
			
		}
			
		
	}

}