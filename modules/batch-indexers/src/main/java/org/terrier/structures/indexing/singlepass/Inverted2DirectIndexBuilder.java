/*
 * Terrier - Terabyte Retriever
 * Webpage: http://terrier.org
 * Contact: terrier{a.}dcs.gla.ac.uk
 * University of Glasgow - School of Computing Science
 * http://www.gla.ac.uk/
 *
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 *
 * The Original Code is Inverted2DirectIndexBuilder.java.
 *
 * The Original Code is Copyright (C) 2004-2020 the University of Glasgow.
 * All Rights Reserved.
 *
 * Contributor(s):
 *   Craig Macdonald (craigm{at}dcs.gla.ac.uk)
 */
package org.terrier.structures.indexing.singlepass;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terrier.applications.CLITool;
import org.terrier.compression.bit.BitIn;
import org.terrier.compression.bit.BitInputStream;
import org.terrier.compression.bit.BitOut;
import org.terrier.compression.bit.BitOutputStream;
import org.terrier.compression.bit.MemorySBOS;
import org.terrier.structures.BasicDocumentIndexEntry;
import org.terrier.structures.Pointer;
import org.terrier.structures.indexing.CompressionFactory.CompressionConfiguration;
import org.terrier.structures.indexing.CompressionFactory;
import org.terrier.structures.DocumentIndexEntry;
import org.terrier.structures.IndexOnDisk;
import org.terrier.structures.IndexUtil;
import org.terrier.structures.LexiconEntry;
import org.terrier.structures.PostingIndexInputStream;
import org.terrier.structures.bit.BitPostingIndex;
import org.terrier.structures.BitIndexPointer;
import org.terrier.structures.SimpleBitIndexPointer;
import org.terrier.structures.bit.BitPostingIndexInputStream;
import org.terrier.structures.indexing.DocumentIndexBuilder;
import org.terrier.structures.AbstractPostingOutputStream;
import org.terrier.structures.postings.IterablePosting;
import org.terrier.structures.postings.bit.BasicIterablePosting;
import org.terrier.structures.postings.bit.FieldIterablePosting;
import org.terrier.utility.ApplicationSetup;
import org.terrier.utility.Files;
import org.terrier.utility.TerrierTimer;
import org.terrier.utility.UnitUtils;

/** Create a direct index from an InvertedIndex. The algorithm is similar to that followed by
  * InvertedIndexBuilder. To summarise, InvertedIndexBuilder builds an InvertedIndex from a DirectIndex.
  * This class does the opposite, building a DirectIndex from an InvertedIndex.
  * <P><B>Algorithm:</b><br>
  * For a selection of document ids
  * -(Scan the inverted index looking for postings with these document ids)
  * -For each term in the inverted index
  * --Select required postings from all the postings of that term
  * --Add these to posting objects that represents each document
  * -For each posting object
  * --Write out the postings for that document
  * <p><b>Notes:</b><br>
  * This algorithm assumes that termids start at 0 and are strictly increasing. This assumption holds true
  * only for inverted indices generated by the single pass indexing method.
  * <p><b>Properties:</b> 
  * <ul>
  * <li><tt>inverted2direct.processtokens</tt> - total number of tokens to attempt each iteration. Defaults to 100000000. Memory usage would more likely
  * be linked to the number of pointers, however as the document index does not contain the number of unique terms in each document, the pointers 
  * calculation is impossible to make.</li>
  * </ul>
  * @author Craig Macdonald
    * @since 2.0 */
public class Inverted2DirectIndexBuilder {
	/** The logger used */
	protected static final Logger logger = LoggerFactory.getLogger(Inverted2DirectIndexBuilder.class);
	/** index currently being used */
	protected IndexOnDisk index;
	
	/** The number of different fields that are used for indexing field information.*/
	protected final int fieldCount;

	/** Indicates whether field information is used. */
	protected final boolean saveTagInformation;

	/** number of tokens limit per iteration */
	protected long processTokens = UnitUtils.parseLong(ApplicationSetup.getProperty("inverted2direct.processtokens", "100000000"));
	
	protected String sourceStructure = "inverted";
	protected String destinationStructure = "direct";
	
	/** Construct a new instance of this builder class */
	public Inverted2DirectIndexBuilder(IndexOnDisk i)
	{
		this.index =  i;
		fieldCount = index.getCollectionStatistics().getNumberOfFields();
		saveTagInformation = fieldCount > 0;
	}

	protected CompressionConfiguration getCompressionConfiguration() {
		return CompressionFactory.getCompressionConfiguration(destinationStructure, index.getCollectionStatistics().getFieldNames(), 0, 0);
	}

	/** create the direct index when the collection contains an existing inverted index */
	@SuppressWarnings("unchecked")
	public void createDirectIndex()
	{
		final long startTime = System.currentTimeMillis();
		if( ! index.hasIndexStructure(sourceStructure))
		{
			logger.error("This index has no "+sourceStructure+" structure, aborting direct index build");
			return;
		}
		if ( index.hasIndexStructure(destinationStructure))
		{
			logger.error("This index already has a "+destinationStructure+" index, no need to create one.");
			return;
		}
		if (index.getIndexProperty("index.terrier.version", "2.0").startsWith("1.") )
		{
			logger.error("Index version from Terrier 1.x - it is likely that the termids are not aligned, and hence df creation would not be correct - aborting direct index build");
			return;
		}
		
		if (! "aligned".equals(index.getIndexProperty("index.lexicon.termids", "")))
		{
			logger.error("This index is not supported by " + this.getClass().getName() + " - termids are not strictly ascending. Try Inv2DirectMultiReduce");
			return;
		}
		CompressionConfiguration directCompressor = getCompressionConfiguration();

		logger.info("Generating a "+destinationStructure+" structure from the "+sourceStructure+" structure");
		int firstDocid = 0;
		final long totalTokens = index.getCollectionStatistics().getNumberOfTokens();
		final String iterationSuffix = (processTokens > totalTokens) 
			? " of 1 iteration" 
			: " of " + (int)((totalTokens%processTokens==0)
				? (totalTokens/processTokens)
				: (totalTokens/processTokens+1)) + " iterations";
		long numberOfTokensFound = 0;	
		int iteration = 0;
		try{
			Iterator<DocumentIndexEntry> diis =  (Iterator<DocumentIndexEntry>) index.getIndexStructureInputStream("document");
			final String offsetsFilename = index.getPath() + ApplicationSetup.FILE_SEPARATOR + index.getPrefix() + "."+destinationStructure+".offsets";
			final DataOutputStream offsetsTmpFile = new DataOutputStream(Files.writeFileStream(offsetsFilename));
			final AbstractPostingOutputStream pos = directCompressor.getPostingOutputStream​( index.getPath() + ApplicationSetup.FILE_SEPARATOR + index.getPrefix() + "."+destinationStructure+ directCompressor.getStructureFileExtension());
			do//for each pass of the inverted file
			{
				iteration++;
				logger.info("Iteration "+iteration  + iterationSuffix);
				//get a copy of the inverted index
				final PostingIndexInputStream iiis = (PostingIndexInputStream) index.getIndexStructureInputStream(sourceStructure);
				//work out how many document we can scan for
				int countDocsThisIteration = scanDocumentIndexForTokens(processTokens, diis);
				//lastDocid = firstDocid + 
				logger.info("Generating postings for "+countDocsThisIteration+" documents starting from id "+firstDocid);
				//get a set of posting objects to save the compressed postings for each of the documents to
				final Posting[] postings = getPostings(countDocsThisIteration);
				//get postings for these documents
				numberOfTokensFound += traverseInvertedFile(iiis, firstDocid, countDocsThisIteration, postings);
				logger.info("Writing postings for iteration "+iteration+" to disk");
				Pointer lastPointer = new SimpleBitIndexPointer();
				for (Posting p : postings) //for each document
				{	
					//logger.debug("Document " + id  + " length="+ p.getDocF());
					
					//get the offsets
					Pointer pointer = null;
					
					//if the document is non-empty
					if (p.getDocF() > 0)
					{					
						//obtain the compressed memory posting list
						final MemorySBOS Docs = p.getDocs();
						//some obscure problem when reading from memory rather than disk.
						//by padding the posting list with some non zero bytes the problem
						//is solved. Thanks to Roicho for working this one out.
						Docs.writeGamma(1);
						Docs.writeGamma(1);
						Docs.pad();
					
						//use a PostingInRun to decompress the postings stored in memory
						final PostingInRun pir = getPostingReader();
						pir.setDf(p.getDocF());
						pir.setTF(p.getTF());
						pir.setPostingSource(new BitInputStream(new ByteArrayInputStream(
							Docs.getMOS().getBuffer())));
						//System.err.println("temp compressed buffer size="+Docs.getMOS().getPos() + " length="+Docs.getMOS().getBuffer().length);
						//decompress the memory postings and write out to the direct file
						pointer = pir.append(pos, -1).getRight();
						lastPointer = pointer;
					} else {
						pointer = lastPointer;
						pointer.setNumberOfEntries(0);
					}

					//take note of the offset for this document in the df
					BitIndexPointer bp = (BitIndexPointer)pointer;
					offsetsTmpFile.writeLong(bp.getOffset());
					offsetsTmpFile.writeByte(bp.getOffsetBits());
					offsetsTmpFile.writeInt(pointer.getNumberOfEntries());
				}// /for document postings
				iiis.close();
				firstDocid = firstDocid + countDocsThisIteration;
			} while(firstDocid < index.getCollectionStatistics().getNumberOfDocuments());

			logger.info("Completed after " + iteration + " iterations");
			assert firstDocid == index.getCollectionStatistics().getNumberOfDocuments() : " firstDocid=" + firstDocid;

			if (numberOfTokensFound != totalTokens)
			{
				logger.warn("Number of tokens found while scanning "+sourceStructure+" structure does not match expected. Expected "
					+index.getCollectionStatistics().getNumberOfTokens()+ ", found " + numberOfTokensFound);
			}
			logger.info("Finishing up: rewriting document index");	
			offsetsTmpFile.close();
			//write the offsets to the DocumentIndex
			final DataInputStream dis = new DataInputStream(Files.openFileStream(offsetsFilename));
			final DocumentIndexBuilder dios = new DocumentIndexBuilder(index, "document-df", fieldCount > 0);
			final Iterator<DocumentIndexEntry> docidInput = (Iterator<DocumentIndexEntry>)index.getIndexStructureInputStream("document");
			
			DocumentIndexEntry die = null;
		    while (docidInput.hasNext())
			{
		    	DocumentIndexEntry old = docidInput.next();
		    	if (fieldCount == 0)
		    	{
		    		die = new BasicDocumentIndexEntry(old);
		    	}
		    	else
		    	{
		    		die = old;
		    	}
		    	die.setOffset(dis.readLong(), dis.readByte());
				die.setNumberOfEntries(dis.readInt());
				dios.addEntryToBuffer(die);
		    }
		    IndexUtil.close(docidInput);
			pos.close();
			IndexUtil.close(diis);
			dis.close();
			Files.delete(offsetsFilename);
			dios.close();
			IndexUtil.renameIndexStructure(index, "document-df", "document");
			
			//only if no fields do we replace the document-factory type
			if (fieldCount == 0)
				index.addIndexStructure("document-factory", BasicDocumentIndexEntry.Factory.class.getName(), "", "");

			//inform the index about the new data structure
			directCompressor.writeIndexProperties(index, "document-inputstream");
			index.flush();//save changes			
			logger.info("Finished generating a "+destinationStructure+" structure from the "+sourceStructure+" structure. Time elapsed: "+((System.currentTimeMillis() - startTime)/1000) + " seconds");

		}catch (IOException ioe) {
			logger.error("Couldnt create a "+destinationStructure+" structure from the "+sourceStructure+" structure", ioe);
		}catch (Exception ioe) {
			logger.error("Couldnt create a "+destinationStructure+" structure from the "+sourceStructure+" structure", ioe);
		} finally {
			
		}
	}

	/** get an array of posting object of the specified size. These will be used to hold
	  * the postings for a range of documents */	
	protected Posting[] getPostings(final int count)
	{
		Posting[] rtr = new Posting[count];
		if (saveTagInformation)
		{
			for(int i=0;i<count;i++)
				rtr[i] = new FieldPosting();
		}
		else
		{
			for(int i=0;i<count;i++)
				rtr[i] = new Posting();
		}
		return rtr;
	}

	/** returns the SPIR implementation that should be used for reading the postings
	  * written earlier */	
	protected PostingInRun getPostingReader()
	{
		if (saveTagInformation)
		{
			return new FieldPostingInRun(fieldCount);
		}
		return new SimplePostingInRun();
	}
	
	/** traverse the inverted file, looking for all occurrences of documents in the given range
	  * @return the number of tokens found in all of the document. */
	protected long traverseInvertedFile(final PostingIndexInputStream iiis, int firstDocid, int countDocuments, final Posting[] directPostings)
		throws IOException
	{
		//foreach posting list in the inverted index
			//for each (in range) posting in list
				//add termid->tf tuple to the Posting array
		long tokens = 0; long numPostings = 0;
		int termId = -1;
		//array recording which of the current set of documents has had any postings written thus far
		boolean[] prevUse = new boolean[countDocuments];
		
		int lastDocid = firstDocid + countDocuments -1;
		Arrays.fill(prevUse, false);
		int[] fieldFs = null;

		TerrierTimer tt = new TerrierTimer("Inverted index processing for this iteration", index.getCollectionStatistics().getNumberOfPointers());
		tt.start();
		try{
			while(iiis.hasNext())
			{
				IterablePosting ip = iiis.next();
				org.terrier.structures.postings.FieldPosting fip = null;
				//after TR-279, termids are not lexographically assigned in single-pass indexers
				//TODO the algorithm of this class does not support TR-279.
				termId = ((LexiconEntry) iiis.getCurrentPointer()).getTermId();
				final int numPostingsForTerm = iiis.getNumberOfCurrentPostings();
				int docid = ip.next(firstDocid);

				//TR-344: check first posting not too great for this pass (c.f. lastDocid)
				if (docid == IterablePosting.EOL  || docid > lastDocid )
					continue;
				
				assert docid >= firstDocid;
				assert docid <= firstDocid + countDocuments;

				if (saveTagInformation)
					fip = (org.terrier.structures.postings.FieldPosting) ip;

				do {
					tokens += ip.getFrequency();
					numPostings++;
					final int writerOffset = docid - firstDocid;
					if (prevUse[writerOffset])
					{
						if (saveTagInformation)
						{
							fieldFs = fip.getFieldFrequencies();
							((FieldPosting)directPostings[writerOffset]).insert(termId, ip.getFrequency(), fieldFs);
						}
						else
							directPostings[writerOffset].insert(termId, ip.getFrequency());
					}
					else
					{
						prevUse[writerOffset] = true;
						if (saveTagInformation)
						{	
							fieldFs = fip.getFieldFrequencies();
							((FieldPosting)directPostings[writerOffset]).writeFirstDoc(termId, ip.getFrequency(), fieldFs);
						}
						else
							directPostings[writerOffset].writeFirstDoc(termId, ip.getFrequency());
					}
					docid = ip.next();
				} while(docid <= lastDocid && docid != IterablePosting.EOL);				
				tt.increment(numPostingsForTerm);
			}
		} finally {
			tt.finished();
		}
		logger.info("Finished scanning "+sourceStructure+" structure, identified "+numPostings+" postings ("+tokens+" tokens) from "+countDocuments + " documents");
		return tokens;
	}
	
	/** Iterates through the document index, until it has reached the given number of terms
	  * @param _processTokens Number of tokens to stop reading the documentindex after
	  * @param docidStream the document index stream to read 
	  * @return the number of documents to process
	  */
	protected static int scanDocumentIndexForTokens(
		final long _processTokens, 
		final Iterator<DocumentIndexEntry> docidStream)
		throws IOException
	{
		long tokens = 0; int i=1;
		while(docidStream.hasNext())
		{
			tokens += docidStream.next().getDocumentLength();
			if (tokens >= _processTokens)
				return i;
			i++;
		}
		return i-1;
	}
	
	public static void main(String[] args) throws Exception {
		CLITool.run(Inverted2DirectCommand.class, args);
	}
}
