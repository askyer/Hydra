package com.findwise.hydra.mongodb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.BackingStoreException;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.findwise.hydra.DatabaseDocument;
import com.findwise.hydra.DatabaseQuery;
import com.findwise.hydra.Document;
import com.findwise.hydra.DocumentFile;
import com.findwise.hydra.DocumentID;
import com.findwise.hydra.DocumentReader;
import com.findwise.hydra.DocumentWriter;
import com.findwise.hydra.JsonException;
import com.findwise.hydra.SerializationUtils;
import com.findwise.hydra.StatusUpdater;
import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.CommandResult;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoException;
import com.mongodb.MongoInternalException;
import com.mongodb.QueryBuilder;
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;
import com.mongodb.gridfs.GridFS;
import com.mongodb.gridfs.GridFSDBFile;
import com.mongodb.gridfs.GridFSInputFile;

/**
 * @author joel.westberg
 *
 */
public class MongoDocumentIO implements DocumentReader<MongoType>, DocumentWriter<MongoType> {
	private static final int MAX_NUMBER_OF_DONE_RETRIES = 10;
	private DBCollection documents;
	private DBCollection oldDocuments;
	private GridFS documentfs;
	private final WriteConcern concern;
	
	private final StatusUpdater updater;
	
	private final Set<String> seenTags = new HashSet<String>();
	
	private static Logger logger = LoggerFactory.getLogger(MongoDocumentIO.class);

	private final long maxDocumentsToKeep;
	private final int oldDocsSize;
	
	public static final String DOCUMENT_COLLECTION = "documents";
	public static final String OLD_DOCUMENT_COLLECTION ="oldDocuments";
	public static final String DOCUMENT_FS = "documents";

	public static final int DEFAULT_RECURRING_INTERVAL = 2000;
	private static final int BYTES_IN_MB = 1048576;
	
	public static final String DOCUMENT_KEY = "document";
	public static final String FILENAME_KEY = "filename";
	public static final String STAGE_KEY = "stage";
	private static final String MIMETYPE_KEY = "contentType";
	private static final String ENCODING_KEY = "encoding";
	
	public MongoDocumentIO(DB db,
			WriteConcern concern,
			long documentsToKeep,
			int oldDocsMaxSizeMB,
			StatusUpdater updater,
			GridFS documentFs) {
		this.concern = concern;
		this.maxDocumentsToKeep = documentsToKeep;
		this.oldDocsSize = oldDocsMaxSizeMB*BYTES_IN_MB;
		this.updater = updater;
		this.documentfs = documentFs;
		
		documents = db.getCollection(DOCUMENT_COLLECTION);
		documents.setObjectClass(MongoDocument.class);
		oldDocuments = db.getCollection(OLD_DOCUMENT_COLLECTION);
		oldDocuments.setObjectClass(MongoDocument.class);
	}
	
	@Override
	public void prepare() {
		capIfNew(documents.getDB(), oldDocsSize, maxDocumentsToKeep);
		oldDocuments = documents.getDB().getCollection(OLD_DOCUMENT_COLLECTION);
		oldDocuments.setObjectClass(MongoDocument.class);
	}
	
	private void capIfNew(DB db, long size, long max) {
		if(!db.getCollectionNames().contains(OLD_DOCUMENT_COLLECTION)) {
			BasicDBObject dbo = new BasicDBObject("create", OLD_DOCUMENT_COLLECTION);
			dbo.put("capped", true);
			dbo.put("size", size);
			dbo.put("max", max);
			CommandResult cr = db.command(dbo);
			if(cr.ok()) {
				logger.info("Created a capped collection for old documents with {size: "+size+", max: "+max+"}");
			}
			else {
				if(db.getCollectionNames().contains(OLD_DOCUMENT_COLLECTION)) {
					logger.debug("Raced to create "+OLD_DOCUMENT_COLLECTION+" collection and lost");
				}
				else {
					logger.error("Unable to create capped collection for old documents, result was: "+cr);
				}
			}
		}
	}
	
	
	/* (non-Javadoc)
	 * @see com.findwise.hydra.DocumentReader#getDocumentFile(com.findwise.hydra.DatabaseDocument)
	 */
	@Override
	public DocumentFile<MongoType> getDocumentFile(DatabaseDocument<MongoType> d, String filename) {
		return getDocumentFile(d.getID(), filename);
	}
	
	private DocumentFile<MongoType> getDocumentFile(DocumentID<MongoType> id, String filename) {
		DBObject query = QueryBuilder.start(DOCUMENT_KEY).is(id.getID()).and(FILENAME_KEY).is(filename).get();
		GridFSDBFile file = documentfs.findOne(query);
		if(file==null) {
			return null;
		}
		
		DocumentFile<MongoType> df =  new DocumentFile<MongoType>(id, file.getFilename(), file.getInputStream(), (String)file.get(STAGE_KEY), file.getUploadDate());

		if(file.containsField(MIMETYPE_KEY)) {
			df.setMimetype((String) file.get(MIMETYPE_KEY));
		}
		
		if(file.containsField(ENCODING_KEY)) {
			df.setEncoding((String) file.get(ENCODING_KEY));
		}
		
		return df;
	}
	
	@Override
	public List<String> getDocumentFileNames(DatabaseDocument<MongoType> d) {
		MongoDocument md = (MongoDocument)d;
		DBObject query = QueryBuilder.start(DOCUMENT_KEY).is(md.getID().getID()).get();
		
		ArrayList<String> list = new ArrayList<String>();
		
		List<GridFSDBFile> files = documentfs.find(query);
		for(GridFSDBFile file : files) {
			list.add(file.getFilename());
		}
		
		return list;
	}
	
	
	/* (non-Javadoc)
	 * @see com.findwise.hydra.DocumentReader#getDocument(com.findwise.hydra.DatabaseQuery)
	 */
	@Override
	public MongoDocument getDocument(DatabaseQuery<MongoType> dbq) {
		DBObject query = ((MongoQuery)dbq).toDBObject();
		MongoDocument doc = (MongoDocument) documents.findOne(query);
		if (doc==null) {
			return null;
		}
		return doc;
	}
	
	/* (non-Javadoc)
	 * @see com.findwise.hydra.DocumentReader#getDocumentById(java.lang.Object)
	 */
	@Override
	public MongoDocument getDocumentById(DocumentID<MongoType> id) {
		return getDocumentById(id, false);
	}

	@Override
	public MongoDocument getDocumentById(DocumentID<MongoType> id, boolean includeInactive) {
		MongoQuery mq = new MongoQuery();
		mq.requireID(id);
		MongoDocument doc = (MongoDocument) documents.findOne(mq.toDBObject());
		if(doc==null && includeInactive) {
			doc = (MongoDocument) oldDocuments.findOne(mq.toDBObject());
		}
		return doc;
	}

	/* (non-Javadoc)
	 * @see com.findwise.hydra.DocumentReader#getDocuments(com.findwise.hydra.DatabaseQuery, int)
	 */
	@Override
	public List<DatabaseDocument<MongoType>> getDocuments(DatabaseQuery<MongoType> dbq, int limit) {
		return getDocuments(dbq, limit, 0);
	}

	public DBCollection getDocumentCollection() {
		return documents;
	}
	
	public DBCollection getOldDocumentCollection() {
		return oldDocuments;
	}

	public void setDocumentCollection(DBCollection documents) {
		this.documents = documents;
	}

	public GridFS getDocumentFS() {
		return documentfs;
	}

	public void setDocumentFS(GridFS documentfs) {
		this.documentfs = documentfs;
	}
	

	
	/* (non-Javadoc)
	 * @see com.findwise.hydra.DocumentWriter#delete(com.findwise.hydra.DatabaseDocument)
	 */
	@Override
	public void delete(DatabaseDocument<MongoType> d) {
		BasicDBObject dbo = new BasicDBObject(MongoDocument.MONGO_ID_KEY, d.getID().getID());
		documents.remove(dbo, concern);
	}
	
	/* (non-Javadoc)
	 * @see com.findwise.hydra.DocumentWriter#deleteAll()
	 */
	@Override
	public void deleteAll() {
		documents.remove(new BasicDBObject());
		documentfs.remove(new BasicDBObject());
	}
	
	/* (non-Javadoc)
	 * @see com.findwise.hydra.DocumentWriter#insert(com.findwise.hydra.DatabaseDocument)
	 */
	@Override
	public boolean insert(DatabaseDocument<MongoType> d) {
		if(d.getID()==null) {
			try {
				for(String key : getNullFields((MongoDocument)d)) {
					d.removeContentField(key);
				}
				documents.insert((MongoDocument) d, concern);
				return true;
			}
			catch (MongoException e) {
				logger.error("INSERT FAILED FOR id:"+d.getID(), e);
			}
		}
		return false;
	}

	/* (non-Javadoc)
	 * @see com.findwise.hydra.DocumentWriter#insert(com.findwise.hydra.DatabaseDocument)
	 */
	@Override
	public boolean insert(DatabaseDocument<MongoType> d, List<DocumentFile<MongoType>> attachments) {
		if(attachments == null || attachments.isEmpty()) {
			return insert(d);
		}

		d.putMetadataField(MongoDocument.COMMITTING_METADATA_FLAG, true);

		if (!insert(d)) {
			return false;
		};

		if (!writeAttachments(d, attachments)) {
			delete(d);
			return false;
		}

		d.putMetadataField(MongoDocument.COMMITTING_METADATA_FLAG, false);
		return update(d);
	}

	private boolean writeAttachments(DatabaseDocument<MongoType> d, List<DocumentFile<MongoType>> attachments) {
		for(DocumentFile<MongoType> attachment: attachments) {
			attachment.setDocumentId(d.getID());
			try {
				write(attachment);
			} catch (IOException e) {
				logger.error(
						String.format(
								"Exception while writing filename:%s for id:%s",
								attachment.getFileName(),
								d.getID()
						),
						e
				);
				return false;
			}
		}
		return true;
	}

	private Set<String> getNullFields(MongoDocument d) {
		Set<String> set = new HashSet<String>();
		for(Map.Entry<String, Object> e : d.getContentMap().entrySet()) {
			if(e.getValue()==null) {
				set.add(e.getKey());
			}
		}
		return set;
	}

	private DBObject getUpdateObject(DBObject update) {
		DBObject dbo = new BasicDBObject();
		dbo.put("$set", update);
		return dbo;
	}
	
	/*
	 * (non-Javadoc)
	 * @see com.findwise.hydra.DatabaseConnector#getAndTag(com.findwise.hydra.DatabaseQuery, java.lang.String)
	 * 
	 * Modifies the mongo DatabaseQuery, call it with 
	 * getAndTag(new MongoQuery(), "tag")) unless you want to modify your query
	 * 
	 */
	@Override
	public MongoDocument getAndTag(DatabaseQuery<MongoType> query, String ... tag) {
		for(String t : tag) {
			ensureIndex(t);
		}
		MongoQuery mq = (MongoQuery)query;
		mq.requireMetadataFieldNotExists(Document.PENDING_METADATA_FLAG);

		/* The document must be fully committed (i.e. all attachments are committed) before we can fetch it
		 * Using not equals to true here (instead of "equals false"), to allow for documents where committing
		 * isn't set at all. */
		mq.requireMetadataFieldNotEquals(Document.COMMITTING_METADATA_FLAG, true);

		for(String t : tag) {
			mq.requireMetadataFieldNotExists(DatabaseDocument.FETCHED_METADATA_TAG+"."+t);
		}
		DBObject update = new BasicDBObject();
		for(String t : tag) {
			update.put(MongoDocument.METADATA_KEY+"."+DatabaseDocument.FETCHED_METADATA_TAG+"."+t, new Date());
		}
		
		DBObject dbo = getUpdateObject(update);

		return findAndModify(mq.toDBObject(), dbo);
	}
	
	private void ensureIndex(String tag) {
		if(!seenTags.contains(tag)) {
			long start = System.currentTimeMillis();
			documents.ensureIndex(MongoDocument.METADATA_KEY+"."+DatabaseDocument.FETCHED_METADATA_TAG+"."+tag);
			logger.info("Ensured index for stage "+tag+" in "+(System.currentTimeMillis()-start)+" ms");
			seenTags.add(tag);
		}
	}
	
	@Override
	public List<DatabaseDocument<MongoType>> getAndTag(DatabaseQuery<MongoType> query, int n, String ... tag) {
		ArrayList<DatabaseDocument<MongoType>> list = new ArrayList<DatabaseDocument<MongoType>>();
		for(int i=0; i<n; i++) {
			MongoDocument d = getAndTag(query, tag);
			if(d==null) {
				break;
			}
			list.add(d);
		}
		return list;
	} 
	
	@Override
	public void write(DocumentFile<MongoType> df) throws IOException {
		QueryBuilder qb = QueryBuilder.start()
				.put(DOCUMENT_KEY)
				.is(df.getDocumentId().getID())
				.and(FILENAME_KEY)
				.is(df.getFileName());
		documentfs.remove(qb.get());
		
		GridFSInputFile input = documentfs.createFile(df.getStream(), df.getFileName());
		input.put(DOCUMENT_KEY, df.getDocumentId().getID());
		input.put(ENCODING_KEY, df.getEncoding());
		input.put(MIMETYPE_KEY, df.getMimetype());
		
		input.save();
		
		/**
		 * This is here because of random failures when trying to do this query from another thread.
		 * 
		 * RemotePipelineTest.testSaveFile() failed 2 or 3 times out of 100.
		 */
		long start = System.currentTimeMillis();
		while (documentfs.findOne(qb.get()) == null) {
			try {
				if(start+1000>System.currentTimeMillis()) {
					throw new IOException("Failed to save the file");
				}
				Thread.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}


	@Override
	public boolean update(DatabaseDocument<MongoType> d) {
		MongoDocument md = (MongoDocument) d;
		
		MongoQuery mdq = new MongoQuery();
		if(d.getID() == null) {
			logger.error("Unable to update document without an ID: "+d);
			return false;
		}
		mdq.requireID(md.getID());
		
		BasicDBObjectBuilder bob = new BasicDBObjectBuilder();
		
		if(md.isActionTouched()) {
			bob.add(MongoDocument.ACTION_KEY, md.getAction().toString());
		}
		
		for(String s : md.getTouchedContent()) {
			if(md.getContentField(s)!=null) {
				bob.add(MongoDocument.CONTENTS_KEY+"."+s, md.getContentField(s));
			}
		}
		for(String s : md.getTouchedMetadata()){
			bob.add(MongoDocument.METADATA_KEY+"."+s, md.getMetadataField(s));
		}
		
		DBObject updateObject = getUpdateObject(bob.get());
		updateObject.putAll(getUnsetObject(getNullFields(md)));
		
		try {
			WriteResult wr = documents.update(mdq.toDBObject(), updateObject, true, false, concern);
			return wr.getN()==1;
		}
		catch (MongoException e) {
			logger.error("UPDATE FAILED FOR id:"+d.getID(), e);
			return false;
		}   catch (IllegalStateException e) {
			logger.error("ILLEGAL STATE EXCEPTION", e);
			return false;
		}
	}
	
	private DBObject getUnsetObject(Set<String> fieldsToUnset) {
		BasicDBObject unsetter = new BasicDBObject();
		BasicDBObject fields = new BasicDBObject();
		for(String s : fieldsToUnset) {
			fields.append(MongoDocument.CONTENTS_KEY+"."+s, 1);
		}
		
		unsetter.put("$unset", fields);
		return unsetter;
	}


	@Override
	public boolean markTouched(DocumentID<MongoType> id, String tag) {
		MongoQuery mq = new MongoQuery();
		mq.requireID(id);
		DBObject update = new BasicDBObject(MongoDocument.METADATA_KEY+"."+DatabaseDocument.TOUCHED_METADATA_TAG+"."+tag, new Date());
		DBObject dbo = getUpdateObject(update);
		
		if(documents.findAndModify(mq.toDBObject(), dbo)==null) {
			return false;
		}
		
		return true;
	}
	
	private DBObject getStampObject(String stage) {
		BasicDBObject object = new BasicDBObject();
		object.put(MongoDocument.DATE_METADATA_SUBKEY, new Date());
		object.put(MongoDocument.STAGE_METADATA_SUBKEY, stage);
		return object;
	}
	
	private void stampMetadataField(DBObject doc, String flag, String stage) {
		if(!doc.containsField(MongoDocument.METADATA_KEY)) {
			doc.put(MongoDocument.METADATA_KEY, new BasicDBObject());
		}
		DBObject metadata = (DBObject)doc.get(MongoDocument.METADATA_KEY);
		
		metadata.put(flag, getStampObject(stage));
		
		doc.put(MongoDocument.METADATA_KEY, metadata);
	}
	
	private boolean markDone(final DatabaseDocument<MongoType> d,
			String stage,
			String stamp) {
		MongoQuery mq = new MongoQuery();
		mq.requireID(d.getID());
		
		DBObject doc = documents.findAndRemove(mq.toDBObject());
		
		if(doc==null) {
			return false;
		}
		
		doc.putAll(((MongoDocument)d).toMap());
		
		stampMetadataField(doc, stamp, stage);
		deleteAllFiles(d);

		return writeToOldDocuments(d, stage, doc);
	}

	/**
	 * Attempt to write a {@link DatabaseDocument} to the {@link #oldDocuments}
	 * collection. If it fails, it will remove the largest content field by replacing the
	 * content with the string "&lt;Removed&gt;" and retry doing this
	 * {@value #MAX_NUMBER_OF_DONE_RETRIES} number of times.
	 * 
	 * <p>
	 * We can do this since the document has been successfully processed, but the cleanup
	 * is failing. We want a document in old documents, but if we can not - then we should
	 * be satisfied with a partial document.
	 * </p>
	 * 
	 * @param document the original {@link DatabaseDocument} which was processed.
	 * @param stage the name of the stage which will be used for logging
	 * @param mongoDocument the MongoDb-document to insert
	 * @return true if it was successful, false if the write failed.
	 */
	private boolean writeToOldDocuments(final DatabaseDocument<MongoType> document,
			String stage,
			DBObject mongoDocument) {
		for (int retryNr = 0; retryNr < MAX_NUMBER_OF_DONE_RETRIES; retryNr++) {
			try {
				oldDocuments.insert(mongoDocument);
				return true;
			} catch (MongoInternalException e) {
				String largestContentField = getLargestContentField(document);
				document.putContentField(largestContentField, "<Removed>");
				document.addError(stage, e);
				mongoDocument.putAll(((MongoDocument) document).toMap());
				logger.error("An error occurred while writing document "
						+ document.getID() + " to " + oldDocuments.getName() + ": "
						+ e.getMessage(),
						e);
			}
		}

		return false;
	}

	private String getLargestContentField(final DatabaseDocument<MongoType> d) {
		return Collections.max(d.getContentFields(), new Comparator<String>() {
			@Override
			public int compare(String a, String b) {
				Integer sizeOfA = d.getContentField(a).toString().length();
				Integer sizeOfB = d.getContentField(b).toString().length();
				return sizeOfA.compareTo(sizeOfB);
			}
		});
	}
	
	@Override
	public boolean markProcessed(DatabaseDocument<MongoType> d, String stage) {
		boolean res = markDone(d, stage, MongoDocument.PROCESSED_METADATA_FLAG);
		
		updater.addProcessed(1);
		
		return res;
	}
	
	@Override
	public boolean markDiscarded(DatabaseDocument<MongoType> d, String stage) {
		boolean res = markDone(d, stage,  MongoDocument.DISCARDED_METADATA_FLAG);
		
		updater.addDiscarded(1);
		
		return res;
	}
	
	@Override
	public boolean markPending(DatabaseDocument<MongoType> d, String stage) {
		MongoQuery mq = new MongoQuery();
		mq.requireID(d.getID());
		DBObject update = new BasicDBObject();
		update.put(MongoDocument.METADATA_KEY+"."+MongoDocument.PENDING_METADATA_FLAG+"."+MongoDocument.DATE_METADATA_SUBKEY, new Date());
		update.put(MongoDocument.METADATA_KEY+"."+MongoDocument.PENDING_METADATA_FLAG+"."+MongoDocument.STAGE_METADATA_SUBKEY, stage);
		DBObject dbo = getUpdateObject(update);
		
		if(documents.findAndModify(mq.toDBObject(), dbo)==null) {
			return false;
		}
		
		return true;
	}
	
	@Override
	public boolean markFailed(DatabaseDocument<MongoType> d, String stage) {
		boolean res = markDone(d, stage, MongoDocument.FAILED_METADATA_FLAG);
		
		updater.addFailed(1);
		
		return res;
	}
	
	private MongoDocument findAndModify(DBObject query, DBObject modification) {
		DBObject c = documents.findAndModify(query, modification);
		
		if(c==null) {
			return null;
		}
		
		MongoDocument md = new MongoDocument();
		md.putAll(c);
		
		return md;
	}

	@Override
	public long getActiveDatabaseSize() {
		return documents.count();
	}
	
	@Override
	public long getInactiveDatabaseSize() {
		return oldDocuments.count();
	}

	@Override
	public MongoTailableIterator getInactiveIterator() {
		try {
			return new MongoTailableIterator(oldDocuments);
		} catch (BackingStoreException e) {
			logger.error("Unable to get Tailable Iterator!", e);
			return null;
		}
	}

	@Override
	public boolean deleteDocumentFile(DatabaseDocument<MongoType> d, String fileName) {
		MongoDocument md = (MongoDocument) d;
		DBObject query = QueryBuilder.start(DOCUMENT_KEY).is(md.getID().getID()).and(FILENAME_KEY).is(fileName).get();
		if (documentfs.getFileList(query).size() != 1) {
			return false;
		}
		
		documentfs.remove(query);
		return true;
		
	}
	
	public void deleteAllFiles(DatabaseDocument<MongoType> d) {
		for(String fileName : getDocumentFileNames(d)) {
			deleteDocumentFile(d, fileName);
		}
	}
	
	@SuppressWarnings("rawtypes")
	@Override
	public DocumentID<MongoType> toDocumentId(Object jsonPrimitive) {
		if (jsonPrimitive instanceof Map) {
			return new MongoDocumentID(new ObjectId((Integer) ((Map) jsonPrimitive).get("_time"),
					(Integer) ((Map) jsonPrimitive).get("_machine"),
					(Integer) ((Map) jsonPrimitive).get("_inc")));
		} else if(jsonPrimitive instanceof String) {
			try {
				Object o = SerializationUtils.toObject((String) jsonPrimitive);
				if (o instanceof Map) {
					return toDocumentId(o);
				}
			} catch (JsonException e) {
				//Do nothing
			}
		}
		logger.error("Serialized ID was not deserialized to map. The type was a "+jsonPrimitive.getClass()+". Was it created by a Hydra database of this type?");
		return null;
	}
	
	@Override
	public DocumentID<MongoType> toDocumentIdFromJson(String json) {
		try {
			return toDocumentId(SerializationUtils.toObject(json));
		} catch (JsonException e) {
			logger.error("Error deserializing json", e);
			return null;
		}
	}
	
	@Override
	public MongoTailableIterator getInactiveIterator(DatabaseQuery<MongoType> query) {
		try {
			return new MongoTailableIterator(((MongoQuery)query).toDBObject(), oldDocuments);
		} catch (BackingStoreException e) {
			logger.error("Unable to get Tailable Iterator!", e);
			return null;
		}
	}

	@Override
	public List<DatabaseDocument<MongoType>> getDocuments(
			DatabaseQuery<MongoType> dbq, int limit, int skip) {
		DBCursor cursor = documents.find(((MongoQuery)dbq).toDBObject()).skip(skip).limit(limit);

		List<DatabaseDocument<MongoType>> list = new ArrayList<DatabaseDocument<MongoType>>();
		while(cursor.hasNext()) {
			cursor.next();
			
			list.add((MongoDocument)cursor.curr());
		}
		
		return list;
	}

	@Override
	public long getNumberOfDocuments(DatabaseQuery<MongoType> q) {
		return documents.getCount(((MongoQuery)q).toDBObject());
	}
}
