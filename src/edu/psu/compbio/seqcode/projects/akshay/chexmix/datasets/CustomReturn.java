package edu.psu.compbio.seqcode.projects.akshay.chexmix.datasets;

public class CustomReturn {
	public double pcc;
	public Vec maxVec1;
	public Vec maxVec2;
	
	/**
	 * This constructor is used as the return object for scanWithBl method in the Bindinlocation class
	 * @param pcc
	 * @param maxVec1
	 * @param maxVec2
	 */
	public CustomReturn(double pcc, Vec maxVec1, Vec maxVec2) {
		this.pcc = pcc;
		this.maxVec1 = maxVec1;
		this.maxVec2 = maxVec2;
	}
	
	//public CustomReturn()
	

}
