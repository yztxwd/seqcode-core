/*
 * Created on Apr 27, 2006
 */
package org.seqcode.data.io;

import java.io.*;
import java.util.*;

import org.seqcode.gsebricks.iterators.SingleIterator;
import org.seqcode.gsebricks.verbs.*;
import org.seqcode.gseutils.*;


/**
 * @author tdanford
 */
public class FASTALoader implements Expander<File,Pair<String,String>> {
    
    public FASTALoader() {
    }

    /* (non-Javadoc)
     * @see org.seqcode.gsebricks.verbs.Expander#execute(java.lang.Object)
     */
    public Iterator<Pair<String, String>> execute(File a) {
        SingleIterator<File> fitr = new SingleIterator<File>(a);
        Iterator<String> lines = new ExpanderIterator<File,String>(new FileLineExpander(), fitr);
        return new LazyFASTAIterator(lines);
    }
    
    private static class LazyFASTAIterator implements Iterator<Pair<String,String>> { 
        
        private Iterator<String> litr;
        private String nextHeader;
        
        public LazyFASTAIterator(Iterator<String> _litr) { 
            litr = _litr;
            nextHeader = null;
            if(litr.hasNext()) { nextHeader = litr.next(); }
        }

        /* (non-Javadoc)
         * @see java.util.Iterator#hasNext()
         */
        public boolean hasNext() {
            return nextHeader != null; 
        }

        /* (non-Javadoc)
         * @see java.util.Iterator#next()
         */
        public Pair<String, String> next() {
            StringBuilder sb = new StringBuilder();
            String header = nextHeader;
            nextHeader = null;
            
            String line = null;
            while(litr.hasNext() && !(line = litr.next()).startsWith(">")) { 
                sb.append(line.trim());
            }
            
            if(litr.hasNext()) { 
                nextHeader = line.trim(); 
            }

            return new Pair<String,String>(header, sb.toString());
        }

        /* (non-Javadoc)
         * @see java.util.Iterator#remove()
         */
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

}
