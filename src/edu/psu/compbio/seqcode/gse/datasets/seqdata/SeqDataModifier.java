package edu.psu.compbio.seqcode.gse.datasets.seqdata;

import java.io.IOException;
import java.security.AccessControlException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import edu.psu.compbio.seqcode.gse.datasets.core.CellLine;
import edu.psu.compbio.seqcode.gse.datasets.core.ExptCondition;
import edu.psu.compbio.seqcode.gse.datasets.core.ExptTarget;
import edu.psu.compbio.seqcode.gse.datasets.core.Lab;
import edu.psu.compbio.seqcode.gse.datasets.core.MetadataLoader;
import edu.psu.compbio.seqcode.gse.datasets.core.MetadataModifier;
import edu.psu.compbio.seqcode.gse.projects.readdb.ACLChangeEntry;
import edu.psu.compbio.seqcode.gse.projects.readdb.Client;
import edu.psu.compbio.seqcode.gse.projects.readdb.ClientException;
import edu.psu.compbio.seqcode.gse.utils.NotFoundException;
import edu.psu.compbio.seqcode.gse.utils.database.DatabaseConnectionManager;
import edu.psu.compbio.seqcode.gse.utils.database.DatabaseException;

/**
 * SeqDataModifier collects interactions that modify the seqdata database  
 * 
 * @author mahony
 *
 */
public class SeqDataModifier {
	public static String role = "seqdata";

	private SeqDataLoader seqLoader;
	private Client client=null;
	private MetadataModifier metaModifier=null;
	private boolean closed=true;
	public SeqDataModifier(SeqDataLoader loader) throws AccessControlException, SQLException {
		seqLoader = loader;
		client = seqLoader.getClient();
		metaModifier = new MetadataModifier();
		if(!seqLoader.getMyUser().isAdmin()){
			throw new AccessControlException("SeqDataModifier: only admins can modify seqdata!");
		}
		closed=false;
	}
	
	public void deleteAlignmentParameters(SeqAlignment align) throws SQLException {
		Connection cxn=null;
		try {
            cxn = DatabaseConnectionManager.getConnection(role);
            Statement del = cxn.createStatement();
            del.execute("delete from alignmentparameters where alignment = " + align.getDBID());
            del.close();
		} catch (SQLException e) {
            throw new DatabaseException(e.toString(),e);
        } finally {
        	if(cxn!=null) try {cxn.close();}catch (Exception ex) {throw new DatabaseException("Couldn't close connection with role "+role, ex); }
        }
	}
	
	public void deleteSeqAlignment(SeqAlignment align) throws SQLException{
		Connection cxn=null;
		try {
            cxn = DatabaseConnectionManager.getConnection(role);
            PreparedStatement deleteAlign = SeqAlignment.createDeleteByIDStatement(cxn);
	        deleteAlign.setInt(1, align.getDBID());
	        deleteAlign.execute();
	        deleteAlign.close();
	        cxn.commit();
		} catch (SQLException e) {
            throw new DatabaseException(e.toString(),e);
        } finally {
        	if(cxn!=null) try {cxn.close();}catch (Exception ex) {throw new DatabaseException("Couldn't close connection with role "+role, ex); }
        }
	}
    
	public void deleteSeqExpt(SeqExpt expt) throws SQLException{
		Connection cxn=null;
		try {
            cxn = DatabaseConnectionManager.getConnection(role);
            Lab lab = expt.getLab();
			ExptCondition cond = expt.getExptCondition();
			ExptTarget target = expt.getExptTarget();
			CellLine cells = expt.getCellLine();
			
			PreparedStatement deleteExpt = SeqExpt.createDeleteByDBID(cxn);
	    	deleteExpt.setInt(1, expt.getDBID());
	    	deleteExpt.execute();
	    	deleteExpt.close();
	    	cxn.commit();
	    	
	    	//Delete core.lab if no SeqExpts depend
	    	if(seqLoader.loadExperiments(lab).size()==0)
	    		deleteLab(lab);
	    	//Delete core.exptcondition if no SeqExpts depend
	    	if(seqLoader.loadExperiments(cond).size()==0)
	    		deleteExptCondition(cond);
	    	//Delete core.expttarget if no SeqExpts depend
	    	if(seqLoader.loadExperiments(target).size()==0)
	    		deleteExptTarget(target);
	    	//Delete core.cellline if no SeqExpts depend
	    	if(seqLoader.loadExperiments(cells).size()==0)
	    		deleteCellLine(cells);
		} catch (SQLException e) {
            throw new DatabaseException(e.toString(),e);
        } finally {
        	if(cxn!=null) try {cxn.close();}catch (Exception ex) {throw new DatabaseException("Couldn't close connection with role "+role, ex); }
        }
	}
	
	public void deleteLab(Lab lab) throws SQLException{
		System.err.println("Deleting lab: "+lab.getName()+"\t"+lab.getDBID());
		metaModifier.deleteLab(lab.getDBID());
	}
	
	public void deleteExptCondition(ExptCondition cond) throws SQLException{
		System.err.println("Deleting condition: "+cond.getName()+"\t"+cond.getDBID());
		metaModifier.deleteCond(cond.getDBID());
	}
	
	public void deleteExptTarget(ExptTarget target) throws SQLException{
		System.err.println("Deleting target: "+target.getName()+"\t"+target.getDBID());
		metaModifier.deleteTarget(target.getDBID());
	}
	
	public void deleteCellLine(CellLine cells) throws SQLException{
		System.err.println("Deleting cell-line: "+cells.getName()+"\t"+cells.getDBID());
		metaModifier.deleteCell(cells.getDBID());
	}
	
	public void coreCleanup() throws SQLException{
		for(Lab lab : seqLoader.getLabs()){
			//Delete core.lab if no SeqExpts depend
			if(seqLoader.loadExperiments(lab).size()==0)
				deleteLab(lab);
		}
		for(ExptCondition cond : seqLoader.getExptConditions()){
			//Delete core.exptcondition if no SeqExpts depend
			if(seqLoader.loadExperiments(cond).size()==0)
				deleteExptCondition(cond);
    	}
		for(ExptTarget target : seqLoader.getExptTargets()){
			//Delete core.expttarget if no SeqExpts depend
			if(seqLoader.loadExperiments(target).size()==0)
				deleteExptTarget(target);
    	}
		for(CellLine cells : seqLoader.getCellLines()){
			//Delete core.cellline if no  SeqExpts depend
			if(seqLoader.loadExperiments(cells).size()==0)
				deleteCellLine(cells);
    	}
	}
	
	public void updateSeqExpt(SeqExpt expt, String updateExptType, String updateLab, String updateCond, String updateTarget, String updateCell, String updateRep, String updatePubSrc, String updatePubID, String updateCollabExptID) throws SQLException, DuplicateDatabaseEntryException{
		Connection cxn=null;
		try {
            cxn = DatabaseConnectionManager.getConnection(role);
            String updateName = updateLab+" "+updateCond+" "+updateTarget+" "+updateCell;
			
			SeqExpt testExpt  = seqLoader.findExperiment(updateName, updateRep);
			if(testExpt!=null && testExpt.getDBID()!=expt.getDBID()) //It's okay if these are the same experiments (you might sometimes want to just update the publication source, etc).
				throw new DuplicateDatabaseEntryException("SeqDataModifier.updateSeqExpt wants to create a duplicate SeqExpt");
			else{
				MetadataLoader mloader = new MetadataLoader();
				PreparedStatement update = SeqExpt.createShortUpdateWithID(cxn);
				update.setString(1, updateName);
		        update.setString(2, updateRep);
		        update.setInt(3, expt.getOrganism().getDBID());
		        update.setInt(4, mloader.getExptType(updateExptType).getDBID());
		        update.setInt(5, mloader.getLab(updateLab).getDBID());
		        update.setInt(6, mloader.getExptCondition(updateCond).getDBID());
		        update.setInt(7, mloader.getExptTarget(updateTarget).getDBID());
		        update.setInt(8, mloader.getCellLine(updateCell).getDBID());
		        update.setString(9, updateCollabExptID);
		        update.setString(10, updatePubSrc);
		        update.setString(11, updatePubID);
		        update.setInt(12, expt.getDBID());
		        update.execute();	            
			
		        try {
				    SeqExpt testExpt2 = seqLoader.loadExperiment(updateName, updateRep);
				} catch (NotFoundException e2) {
		            // failed again means the insert failed.  you lose 
		        	cxn.rollback();
		            throw new DatabaseException("Couldn't update experiment for " + updateName + "," + updateRep);
		        }
		        update.close();
			}
		} catch (SQLException e) {
            throw new DatabaseException(e.toString(),e);
        } finally {
        	if(cxn!=null) try {cxn.close();}catch (Exception ex) {throw new DatabaseException("Couldn't close connection with role "+role, ex); }
        }
	}
	
	public void updateSeqAlignmentHitCounts(SeqAlignment align, Integer singlecount, Float singleweight,Integer singletype2count, Float singletype2weight, Integer paircount, Float pairweight) throws SQLException{
		Connection cxn=null;
		try {
            cxn = DatabaseConnectionManager.getConnection(role);
            int id = align.getDBID();
			PreparedStatement update = SeqAlignment.createUpdateHitsAndWeights(cxn);
	        System.err.println("Updating counts for alignment: "+id+" ("+align.getName()+")");
	        System.err.println("\tnumhits="+singlecount);
	        System.err.println("\ttotalweight="+singleweight);
	        System.err.println("\tnumtype2hits="+singletype2count);
	        System.err.println("\ttotaltype2weight="+singletype2weight);
	        System.err.println("\tnumpairs="+paircount);
	        System.err.println("\ttotalpairweight="+pairweight);
	        update.setInt(1, singlecount);
	        update.setFloat(2, singleweight);
	        update.setInt(3, singletype2count);
	        update.setFloat(4, singletype2weight);
	        update.setInt(5, paircount);
	        update.setFloat(6, pairweight);
	        update.setInt(7, id);
	        update.execute();
	        update.close();
	        cxn.commit();
		} catch (SQLException e) {
            throw new DatabaseException(e.toString(),e);
        } finally {
        	if(cxn!=null) try {cxn.close();}catch (Exception ex) {throw new DatabaseException("Couldn't close connection with role "+role, ex); }
        }
	}
	
	public void updateSeqAlignmentPermissions(SeqAlignment align, String permissions) throws SQLException{
		Connection cxn=null;
		try {
            cxn = DatabaseConnectionManager.getConnection(role);
            PreparedStatement permUpdate = SeqAlignment.createUpdatePermissions(cxn);
	    	permUpdate.setString(1, permissions);
	    	permUpdate.setInt(2, align.getDBID());
	    	permUpdate.execute();
	    	permUpdate.close();
		} catch (SQLException e) {
            throw new DatabaseException(e.toString(),e);
        } finally {
        	if(cxn!=null) try {cxn.close();}catch (Exception ex) {throw new DatabaseException("Couldn't close connection with role "+role, ex); }
        }
	}
	
	/**
	 * Update the permissions for a SeqAlignment
	 * SeqAlignment align: alignment to change
	 * String princ : user name
	 * String op : operation [add|delete] 
	 * String acl [read|write|admin]
	 * @param princ
	 */
	public void changeAlignmentACL(SeqAlignment align, String princ, String op, String acl){
		Set<ACLChangeEntry> changes = new HashSet<ACLChangeEntry>();
		changes.add(new ACLChangeEntry(ACLChangeEntry.opCode(op),
                                   ACLChangeEntry.aclCode(acl),
                                   princ));
		try {
			client.setACL(new Integer(align.getDBID()).toString(), changes);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClientException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Update multiple permissions for a SeqAlignment
	 * SeqAlignment align: alignment to change
	 * String[] princs : user name
	 * String[] ops : operation [add|delete] 
	 * String[] acls [read|write|admin]
	 * @param princ
	 */
	public void changeAlignmentACLmulti(SeqAlignment align, String[] princs, String[] ops, String[] acls){
		if(princs.length==ops.length && ops.length==acls.length){
			Set<ACLChangeEntry> changes = new HashSet<ACLChangeEntry>();
			for(int i=0; i<princs.length; i++)
				changes.add(new ACLChangeEntry(ACLChangeEntry.opCode(ops[i]),
	                                   ACLChangeEntry.aclCode(acls[i]),
	                                   princs[i]));
			try {
				client.setACL(new Integer(align.getDBID()).toString(), changes);
			} catch (IOException e) {
				e.printStackTrace();
			} catch (ClientException e) {
				e.printStackTrace();
			}
		}else{
			System.err.println("changeAlignmentACLmulti: input arrays should be the same lengths");
		}
	}
	
	public class DuplicateDatabaseEntryException extends Exception{
		public DuplicateDatabaseEntryException(String message){
			super(message);
		}
	}

}
