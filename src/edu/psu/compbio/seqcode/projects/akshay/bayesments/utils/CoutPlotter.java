package edu.psu.compbio.seqcode.projects.akshay.bayesments.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import edu.psu.compbio.seqcode.projects.akshay.bayesments.framework.Config;

public class CoutPlotter {
	public float[] counts;
	public Config config;
	public String name_tag;
	
	public CoutPlotter(float[] counts, Config config, String name) {
		this.counts = counts;
		this.config =config;
		this.name_tag = name;
	}
	
	public void plot(){
		// Writing the data to the intermediate file
		try{
			String fdata = config.getOutputInterDir().getAbsolutePath()+File.separator
					+name_tag+"_data.tab";
			String imageName = config.getOutputImagesDir().getAbsolutePath()+File.separator
					+name_tag+"_boxplot.png";
			FileWriter fout = new FileWriter(fdata);
			for(int i=0; i<counts.length; i++){
				fout.write(Double.toString(counts[i])+"\n");
			}
			fout.close();
			
			//writing the R script
			
			String rscript = config.getOutputInterDir().getAbsolutePath()+File.separator+
					"rscript.R";
			FileWriter rout = new FileWriter(rscript);
			rout.write("dat = read.table("+"\""+fdata+"\""+")\n"+
						"dat_sorted = sort(dat[,1])\n"+
						"maxx = max(dat_sorted)\n"+
						"breaks = seq(0,maxx,10)\n"+
						"cuts = cut(dat_sorted,breaks,right=FALSE)\n"+
						"freqs=table(cuts)\n"+
						"cumfreq0 = c(0, cumsum(freqs))\n"+
						"png("+"\""+imageName+"\""+")\n"+
						"plot(breaks,cumfreq0,xlab=\"number of tags\", ylab=\"number of events\")");
			
			rout.close();
			
			//running the rscript
			
			Process proc = Runtime.getRuntime().exec("Rscript "+rscript);
			proc.destroy();
			
			
			
			
		}
		catch(IOException e){
			e.printStackTrace();
		}
		
		
		
	}
	

}
