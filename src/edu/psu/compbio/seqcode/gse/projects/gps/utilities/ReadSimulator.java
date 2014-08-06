package edu.psu.compbio.seqcode.gse.projects.gps.utilities; 

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import edu.psu.compbio.seqcode.genome.Genome;
import edu.psu.compbio.seqcode.genome.location.Region;
import edu.psu.compbio.seqcode.gse.projects.gps.BindingModel;
import edu.psu.compbio.seqcode.gse.projects.gps.ReadHit;
import edu.psu.compbio.seqcode.gse.tools.utils.Args;
import edu.psu.compbio.seqcode.gse.utils.Pair;
import edu.psu.compbio.seqcode.gse.utils.io.IOUtil;

/**
 * Simulates reads using BindingModels. <br> 
 * 
 * The two most basic files that should be loaded are: <tt>binding_model_file</tt> 
 * and <tt>sites_file</tt>. <br>
 * Each entry of the <tt>binding_model_file</tt> should have the form: <br>
 * <pre> relative_pos_to_binding_start	binding_strength</pre>  <br>
 * E.g. <br>
 * <pre>
 * -4	4872
 * -3	4928
 * -2	4987
 * -1	5014
 *  0	5045
 *  1	4919
 *  2	4952
 *  3	4987
 * </pre>
 * Each entry of the <tt>sites_file</tt> should have the form: <br>
 * <pre> 
 * position_of_event1	strength_of_event1
 * position_of_event2	strength_of_event2
 * ...
 * </pre> 
 * E.g. <br>
 * <pre>
 *   50 20
 *  150 20
 *  300 10
 *   </pre>
 * 
 * @author shaunmahony
 *
 */
public class ReadSimulator {

	public final int DEFAULT_PEAK_LOCATION = 500;
	
	private BindingModel model;
	private int min, max;	// the position range
	private int numReads;
	
	private long randSeed;
	private long noiseRandSeed;
	private List<ReadHit> reads;
	private Genome fakeGen;
	private List<Pair<Integer, Integer>> events; //Pair : Position / Strength(read count)
	private double[] forProbLand;
	private double[] revProbLand;
	private double[] forProbCumul;
	private double[] revProbCumul;
	private int rLen=26;
	private int numNoisyReads;
	
	/**
	 * This proportion of reads will be generated entirely randomly (Poisson) <br>
	 * The higher the <tt>noiseProbability</tt>, the most likely the read is to
	 * be generated by a Poisson distribution.
	 */
	private double noiseProbability=0.1;
	
	public ReadSimulator(BindingModel m, File sFile){
		model=m;
		reads = new ArrayList<ReadHit>();
		fakeGen = new Genome("Z");
		
		//Load the file
		try {
			events = new LinkedList<Pair<Integer,Integer>>(); 			
			BufferedReader reader = new BufferedReader(new FileReader(sFile));
	        String line;
	        min=Integer.MAX_VALUE;
	        max=Integer.MIN_VALUE;
	        numReads = 0;
	        numNoisyReads = 0;
	        while ((line = reader.readLine()) != null) {
	            line = line.trim();
	            String[] words = line.split("\\s+");
	            if(words.length >= 2){
	            	int pos = Integer.parseInt(words[0]);
	            	int readNum = Integer.parseInt(words[1]);
	            	Pair<Integer,Integer> p = new Pair<Integer,Integer>(pos, readNum);
	            	events.add(p);
	            	
	            	min = Math.min(min, pos);
	            	max = Math.max(max, pos);
	            	numReads += readNum;
	            }
	        }
	        min = Math.max(0, min-model.getRange());
	        max = max+model.getRange();

		} 
		catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		initProbabilities();
	}
	
	/**
	 * 
	 * @param m Binding model
	 * @param events Kx2 matrix 
	 * 				 First column represents the positions of the events. 
	 * 			     Second column represents the strengths of the events.
	 */
	public ReadSimulator(BindingModel m, int[][] events){
		model=m;
		reads = new ArrayList<ReadHit>();
		fakeGen = new Genome("Z");
		
		//Load the file
		this.events = new LinkedList<Pair<Integer,Integer>>(); 			
		min=Integer.MAX_VALUE;
		max=Integer.MIN_VALUE;
		numReads = 0;
		numNoisyReads = 0;
		for(int i = 0; i < events.length; i++) {
			int pos     = events[i][0];
			int readNum = events[i][1];
			Pair<Integer,Integer> p = new Pair<Integer,Integer>(pos, readNum);
			this.events.add(p);
			min = Math.min(min, pos);
			max = Math.max(max, pos);
			numReads += readNum;
		}

		min = Math.max(0, min-model.getRange());
		max = max+model.getRange();
		initProbabilities();
	}

	/**
	 * Constructor for simple read generation
	 * @param m
	 * @param genomeFile a text file specifying chrom and length information
	 */
	public ReadSimulator(BindingModel m, String genomeFile){
		model=m;
		reads = new ArrayList<ReadHit>();
		fakeGen = new Genome("Genome", new File(genomeFile), true);
		numNoisyReads = 0;
		events = new LinkedList<Pair<Integer,Integer>>(); 
		min = Math.max(0, DEFAULT_PEAK_LOCATION - model.getRange());
		max = DEFAULT_PEAK_LOCATION + model.getRange();
		Pair<Integer,Integer> p = new Pair<Integer,Integer>(DEFAULT_PEAK_LOCATION, 1);
    	events.add(p);
		
		initProbabilities();
	}
	
	private void initProbabilities(){
		//Initialize the probability landscape
		int regionLen = max+1;
		forProbLand=new double[regionLen]; revProbLand=new double[regionLen];
		forProbCumul=new double[regionLen]; revProbCumul=new double[regionLen];
		for(int i=min; i<=max; i++){
			forProbLand[i]=0; revProbLand[i]=0;
			forProbCumul[i]=0; revProbCumul[i]=0;
		}
		
		//Impose the binding events on the probability landscape
		for(int i=min; i<=max; i++){
			for(Pair<Integer,Integer> e : events){
				int start    = e.car();		// read position
				int strength = e.cdr();		// event strength
				int forDist  = i-start;
				int revDist  = start-i;
				
				forProbLand[i]+=strength*model.probability(forDist);
				revProbLand[i]+=strength*model.probability(revDist);
			}
		}
		
		//Set the cumulative scores
		double fTotal=0, rTotal=0;
		for(int i=min; i<=max; i++){
			fTotal+=forProbLand[i];
			rTotal+=revProbLand[i];
			forProbCumul[i]=fTotal;
			revProbCumul[i]=rTotal;
		}
		//Normalize
		for(int i=min; i<=max; i++){
			forProbLand[i]=forProbLand[i]/fTotal;
			revProbLand[i]=revProbLand[i]/rTotal;
			forProbCumul[i]=forProbCumul[i]/fTotal;
			revProbCumul[i]=revProbCumul[i]/rTotal;
		}
	}
	
	//Accessors
	public void setNoiseProb(double p){
		if(p < 0.0 || p > 1.0) { throw new IllegalArgumentException("p has to be a number between 0.0 and 1.0"); }
		noiseProbability = p;
	}
	
	public Region getSimRegion(){
		return new Region(fakeGen, fakeGen.getChromName(-1), 0, max);
	}
	
	public int getNumReads() { return numReads; }
	
	//Simulate reads
	public List<ReadHit> simulateBothStrands(){
		simulate(numReads/2, true);
		simulate(numReads-numReads/2, false);
		return(reads);
	}
	public List<ReadHit> simulateBothStrands(int numReads){
		simulate(numReads/2, true);
		simulate(numReads-numReads/2, false);
		return(reads);
	}
	public List<ReadHit> simulate(int numReads)	{
		return(simulate(numReads, true));
	}
	public List<ReadHit> simulate(int numReads, boolean forwardStrand){
		if(randSeed == noiseRandSeed)
			throw new IllegalArgumentException("If you input the same seed for both read and noise generator, degenerate results will be generated.");
		
		Random generator = new Random();
		generator.setSeed(randSeed++);
		Random noiseGenerator = new Random();
		noiseGenerator.setSeed(noiseRandSeed++);
		
		ReadHit r=null;
		for(int i=0; i<numReads; i++){
			double rand = generator.nextDouble();
			double noiserand = noiseGenerator.nextDouble();
			
			// The read comes from the background (noise) model
			if(noiserand < noiseProbability){
				int start = (int)(min+rand*(max-min));
				if (forwardStrand)
					r = new ReadHit(fakeGen,i, fakeGen.getChromName(-1), start, start+rLen-1, '+');
				else
					r = new ReadHit(fakeGen,i, fakeGen.getChromName(-1), Math.max(0, start-rLen+1), start, '-');
				
				numNoisyReads++;
			}
			
			// The read is being generated by the event
			else{
				int fivePrimeEnd=0;
				//Find the probability interval
				if(forwardStrand){
					for(int j=min; j<=max; j++){
						if(forProbCumul[j] > rand){
							fivePrimeEnd=j;
							break;
						}
					}
					r = new ReadHit(fakeGen,i, fakeGen.getChromName(-1), fivePrimeEnd, fivePrimeEnd+rLen-1, '+');			
				}
				
				else{
					for(int j=max; j>=min; j--){
						if(revProbCumul[j] < rand){
							fivePrimeEnd=j+1;
							break;
						}
					}
					r = new ReadHit(fakeGen,i, fakeGen.getChromName(-1), Math.max(0, fivePrimeEnd-rLen+1), fivePrimeEnd, '-');
				}
			}
			reads.add(r);
		}
		return(reads);
	}
	// clean up
	public void restart(){
		reads.clear();
	}
	
	//Write an output file of read positions
	//text format for matlab
	public void printToFile(String filename){
		try {
			FileWriter fout = new FileWriter(filename);
			fout.write("Z:"+min+"-"+max+"\n");
			for(ReadHit r : reads){
				int str = r.getStrand()=='+' ? 1:-1;
				//fout.write(r.getFivePrime()+"\t"+r.getStrand()+"\n");
				fout.write(r.getFivePrime()+"\t"+str+"\n");
			}
			fout.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void getPosAndStrand(List<Integer> posList, List<Character> strandList) {
		for(ReadHit r:reads) {
			posList.add(r.getFivePrime());
			strandList.add(r.getStrand());
		}		
	}// end of getPosAndStrand method
	
	public List<Pair<Integer, Integer>> getEvents() { return events; }
	
	//Write an eland file
	public void printToElandFile(String filename){
		try {
			FileWriter fout = new FileWriter(filename);
			//fout.write("Z:1-"+regionLen+"\n");
			int total=0;
			for(ReadHit r : reads){
				total++;
				String name = ">Fake"+total;
				char str = r.getStrand()=='+' ? 'F':'R';
				//fout.write(r.getFivePrime()+"\t"+r.getStrand()+"\n");
				fout.write(name+"\tNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNNN\tU0\t0\t0\t0\t"+r.getChrom()+"\t"+r.getStart()+"\t"+str+"\n");
			}
			fout.close();
		} 
		catch (IOException e) { e.printStackTrace(); }
	}
	
	/**
	 * The arguments should have the form:										<br>
	 * --model <tt>binding_model_file</tt>										<br>
     * --sites <tt>binding_events_file</tt>										<br>
	 * --out <tt>output_path</tt>												<br>
     * [--randSeed <tt>read_random_seed_value</tt>]								<br>
	 * [--noiseRandSeed <tt>noise_random_seed_value</tt>]						<br>
	 * [--noise <tt>noise_probability</tt>]									    <br>
	 * [--matlab]																<br>
	 * [--eland]																<br>
     *																			<br>
	 * E.g. an example run is:													<br>
	 * --model /Users/gio_fou/Desktop/synthdata/YL_Oct4_bm.txt					<br>
     * --sites /Users/gio_fou/Desktop/synthdata/events.txt						<br>
	 * --out /Users/gio_fou/Desktop/synthdata/									<br>
     * --randSeed 0																<br>
	 * --noiseRandSeed 1														<br>
	 * --noise 0.1																<br>
	 * --matlab
	 * @param args
	 */
	public static void main(String[] args) {
		
		if(Args.parseArgs(args).contains("model") && Args.parseArgs(args).contains("sites")){
			
			String bmfile      = Args.parseString(args, "model", null);
			String sitefile    = Args.parseString(args, "sites", null);
			long randSeed      = Args.parseLong(args, "randSeed", new Random().nextLong());
			long noiseRandSeed = Args.parseLong(args, "noiseRandSeed", new Random().nextLong());
			double noiseProb   = Args.parseDouble(args, "noise", 0.1);
			int numReads       = Args.parseInteger(args, "numReads", 0);
			String outPath     = Args.parseString(args, "out", "");
			
			File mFile = new File(bmfile);
			if(!mFile.isFile()){System.err.println("Invalid file name");System.exit(1);}
			
			File sFile = new File(sitefile);
			if(!sFile.isFile()){System.err.println("Invalid file name");System.exit(1);}
	        
			//File loaded, make a BindingModel
	        BindingModel bm = new BindingModel(mFile);
	        
	        //Initialize the ReadSimulator
	        ReadSimulator sim = new ReadSimulator(bm, sFile);	        
	        sim.setRandSeed(randSeed);  
	        sim.setNoiseRandSeed(noiseRandSeed);
	        sim.setNoiseProb(noiseProb);
	        if( numReads == 0 ) { numReads = sim.getNumReads(); }  
	        sim.simulateBothStrands(numReads);
	        
	        System.out.println("Landscape length: " + sim.getSimRegion().getWidth());
	        
	        List<Integer> pos      = new ArrayList<Integer>();
	        List<Character> strand = new ArrayList<Character>();
	        sim.getPosAndStrand(pos, strand);
	        
	        IOUtil.write2file(outPath + "x_obs.txt", pos.toArray(new Integer[0]));
	        IOUtil.write2file(outPath + "strands.txt", strand.toArray(new Character[0]));
	        
	        if (Args.parseArgs(args).contains("eland") || Args.parseArgs(args).contains("matlab"))
    		{	
		        if(Args.parseArgs(args).contains("eland"))
		        	sim.printToElandFile(outPath + "reads" + ".eland");
		        if(Args.parseArgs(args).contains("matlab"))
		        	sim.printToFile(outPath + "reads" + ".matlab");
    		}
	        else
	        	sim.printToFile(outPath + "reads" + ".txt");	        
		}
		else{
			System.out.println("Usage: ReadSimulator \n--model bindingmodel \n--sites file \n--num numReads"+
								"\n--out outfile\n--noise noiseProb[--eland] [--matlab]\n");
		}
	}

	public long getRandSeed() {
		return randSeed;
	}

	public void setRandSeed(long randSeed) {
		this.randSeed = randSeed;
	}

	public long getNoiseRandSeed() {
		return noiseRandSeed;
	}

	public void setNoiseRandSeed(long noiseRandSeed) {
		this.noiseRandSeed = noiseRandSeed;
	}

}
