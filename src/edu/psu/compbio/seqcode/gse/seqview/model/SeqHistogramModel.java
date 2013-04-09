package edu.psu.compbio.seqcode.gse.seqview.model;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

import cern.jet.random.Poisson;
import cern.jet.random.engine.DRand;

import edu.psu.compbio.seqcode.gse.datasets.general.Point;
import edu.psu.compbio.seqcode.gse.datasets.general.ProfileRegion;
import edu.psu.compbio.seqcode.gse.datasets.general.Region;
import edu.psu.compbio.seqcode.gse.datasets.seqdata.*;
import edu.psu.compbio.seqcode.gse.projects.chiapet.Neighborhood;
import edu.psu.compbio.seqcode.gse.projects.readdb.*;
import edu.psu.compbio.seqcode.gse.utils.probability.NormalDistribution;
import edu.psu.compbio.seqcode.gse.utils.stats.StatUtil;

/**
 * Data model for chipseq histogram.  Separate methods for retrieving
 * plus and minus strand results
 */
public class SeqHistogramModel extends SeqViewModel implements RegionModel, Runnable {
    
    private Client client;
    private TreeMap<Integer,Float> resultsPlus, resultsMinus, resultsPval;
    private Set<SeqAlignment> alignments;
    private Set<String> ids;
    private SeqHistogramModelProperties props;

    private Region region;
    private boolean newinput;
    
    private double[] kernel;
    private double[] readdist;
	private double[] eventdist;
	private double[] condist;
	private int cutoff;
	private Map<Integer,String> revChromMap;
	private Poisson poisson = new Poisson(1, new DRand());

    public SeqHistogramModel (SeqAlignment a) throws IOException, ClientException {
        alignments = new HashSet<SeqAlignment>();
        alignments.add(a);
        props = new SeqHistogramModelProperties();
        region = null;
        newinput = false;
        client = new Client();
        ids = new HashSet<String>();
        ids.add(Integer.toString(a.getDBID()));
    }
    public SeqHistogramModel (Collection<SeqAlignment> a) throws IOException, ClientException {
        alignments = new HashSet<SeqAlignment>();
        alignments.addAll(a);
        props = new SeqHistogramModelProperties();
        region = null;
        newinput = false;
        client = new Client();
        ids = new HashSet<String>();
        for (SeqAlignment align : alignments) {
            ids.add(Integer.toString(align.getDBID()));
        }
    }    
    public SeqHistogramModelProperties getProperties() {return props;}
    
    
    public void clearValues() {
        resultsPlus = null;
        resultsMinus = null;
        resultsPval = null;
    }
    public Region getRegion() {return region;}
    public void setRegion(Region r) {
        if (newinput == false) {
            if (!r.equals(region)) {
                region = r;
                newinput = true;
            } else {
                notifyListeners();
            }
        }
    }
    public boolean isReady() {return !newinput;}
    public Map<Integer,Float> getPlus() {return resultsPlus;}
    public Map<Integer,Float> getMinus() {return resultsMinus;}
    public Map<Integer,Float> getPval() {return resultsPval;}
    public synchronized void run() {
        while(keepRunning()) {
            try {
                if (!newinput) {
                    wait();
                }
            } catch (InterruptedException ex) {

            }
            if (newinput) {
                try {
                    int width = props.BinWidth;
                    boolean extension = props.ReadExtension;
                    // for GaussianKernel, get 1bp resolution data
                    if (props.GaussianKernelVariance!=0 && region.getWidth()<=1000){ 
                    	width = 1;
                    }
                    resultsPlus = null;
                    resultsMinus = null;
                    resultsPval = null;
                    if (props.UseWeights) {
                        if (!props.ShowPairedReads || props.ShowSingleReads) {
                        	try{
	                            resultsPlus = Aggregator.mergeHistogramsFF(resultsPlus,
	                                                                       client.getWeightHistogram(ids,
	                                                                                                 region.getGenome().getChromID(region.getChrom()),
	                                                                                                 false,
	                                                                                                 extension,
	                                                                                                 width,
	                                                                                                 (int)props.DeDuplicate,
	                                                                                                 region.getStart(),
	                                                                                                 region.getEnd(),
	                                                                                                 null,
	                                                                                                 true));
	                            
	                            resultsMinus = Aggregator.mergeHistogramsFF(resultsMinus,
	                                                                        client.getWeightHistogram(ids,
	                                                                                                  region.getGenome().getChromID(region.getChrom()),
	                                                                                                  false,
	                                                                                                  extension,
	                                                                                                  width,
	                                                                                                  (int)props.DeDuplicate,
	                                                                                                  region.getStart(),
	                                                                                                  region.getEnd(),
	                                                                                                  null,
	                                                                                                  false));
                        	}catch (Exception ex) {
                                //Fail silently if there are no single read alignments
                            }
                        }
                        if (props.ShowPairedReads) {
                        	try{
	                            resultsPlus = Aggregator.mergeHistogramsFF(resultsPlus,
	                                                                    client.getWeightHistogram(ids,
	                                                                                              region.getGenome().getChromID(region.getChrom()),
	                                                                                              true,
	                                                                                              extension,
	                                                                                              width,
	                                                                                              (int)props.DeDuplicate,
	                                                                                              region.getStart(),
	                                                                                              region.getEnd(),
	                                                                                              null,
	                                                                                              true));
	                            
	                            resultsMinus = Aggregator.mergeHistogramsFF(resultsMinus,
	                                                                     client.getWeightHistogram(ids,
	                                                                                               region.getGenome().getChromID(region.getChrom()),
	                                                                                               true,
	                                                                                               extension,
	                                                                                               width,
	                                                                                               (int)props.DeDuplicate,
	                                                                                               region.getStart(),
	                                                                                               region.getEnd(),
	                                                                                               null,
	                                                                                               false));
                        	}catch (Exception ex) {
                                //Fail silently if there are no paired read alignments
                            }
                        }

                    } else {
                        if (!props.ShowPairedReads || props.ShowSingleReads) {
                        	try{
	                            resultsPlus = Aggregator.mergeHistogramsIF(client.getHistogram(ids,
	                                                                                           region.getGenome().getChromID(region.getChrom()),
	                                                                                           false,
	                                                                                           extension,
	                                                                                           width,
	                                                                                           (int)props.DeDuplicate,
	                                                                                           region.getStart(),
	                                                                                           region.getEnd(),
	                                                                                           null,
	                                                                                           true),
	                                                                    resultsPlus);
	                            
	                            resultsMinus = Aggregator.mergeHistogramsIF(client.getHistogram(ids,
	                                                                                            region.getGenome().getChromID(region.getChrom()),
	                                                                                            false,
	                                                                                            extension,
	                                                                                            width,
	                                                                                            (int)props.DeDuplicate,
	                                                                                            region.getStart(),
	                                                                                            region.getEnd(),
	                                                                                            null,
	                                                                                            false),
	                                                                     resultsMinus);
                        	}catch (Exception ex) {
                                //Fail silently if there are no single read alignments
                            }
                        }
                        if (props.ShowPairedReads) {
                        	try{
	                            resultsPlus = Aggregator.mergeHistogramsIF(
	                                                                    client.getHistogram(ids,
	                                                                                        region.getGenome().getChromID(region.getChrom()),
	                                                                                        true,
	                                                                                        extension,
	                                                                                        width,
	                                                                                        (int)props.DeDuplicate,
	                                                                                        region.getStart(),
	                                                                                        region.getEnd(),
	                                                                                        null,
	                                                                                        true),
	                                                                       resultsPlus);
	                            
	                            resultsMinus = Aggregator.mergeHistogramsIF(
	                                                                     client.getHistogram(ids,
	                                                                                         region.getGenome().getChromID(region.getChrom()),
	                                                                                         true,
	                                                                                         extension,
	                                                                                         width,
	                                                                                         (int)props.DeDuplicate,
	                                                                                         region.getStart(),
	                                                                                         region.getEnd(),
	                                                                                         null,
	                                                                                         false),
	                                                                       resultsMinus);
                        	}catch (Exception ex) {
                                //Fail silently if there are no paired read alignments
                            }
                        }
                    }
                } catch (Exception ex) {
                    //ex.printStackTrace();
                }
                if(resultsPlus==null || resultsMinus==null){
                    // assign empty output.  This is useful because Client
                    // throws an exception for non-existant chromosomes, such
                    // as those for which there were no alignment results
                    resultsPlus = new TreeMap<Integer,Float>();
                    resultsMinus = resultsPlus;
                }

                newinput = false;
                notifyListeners();
            }
        }
        client.close();
    }
 }