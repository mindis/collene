package collene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.cassandraunit.CassandraCQLUnit;
import org.cassandraunit.dataset.cql.ClassPathCQLDataSet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;


@RunWith(Parameterized.class)
public class TestDirectoryMerging {
    
    private static final boolean isTravis = System.getenv().containsKey("TRAVIS") && System.getenv().get("TRAVIS").equals("true");
        
    // chances are that I'm breaking rules trying to create a static CassandraCQLUnit instance. But this is how I got
    // it to work. Also, it was important to me that I use the same cassandra database for each test.
    public static CassandraCQLUnit cassandra = new CassandraCQLUnit(new ClassPathCQLDataSet("ddl.cql", "collene"), "/cassandra.yaml", "127.0.0.1", 9042) {{
        try {
            if (!isTravis) {
                this.before();
            }
            this.load();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }};
    
    // todo: a bucket of directories that need to be cleaned up.
    
    private static final Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_4_9);
    
    private Directory d0;
    private Directory d1;
    private Directory d2;
    
    public TestDirectoryMerging(Directory d0, Directory d1, Directory d2) {
        this.d0 = d0;
        this.d1 = d1;
        this.d2 = d2;
    }
    
    @Test
    public void testSimpleMerge() throws Exception {
        writeDocuments("one", 100, d0);
        writeDocuments("two", 100, d1);
        writeDocuments("three", 100, d2);
        
        for (Directory d : new Directory[] {d0, d1, d2}) {
            System.out.println("Files for dir " + d.toString());
            for (String f : d.listAll()) {
                System.out.println(" " + f);
            }
        }
        
        IndexWriter writer = makeWriter(d0, null);
        writer.addIndexes(d1, d2);
        writer.close(true);
        
        
        // now do a search, see if everything is there.
        IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(d0));
        QueryParser parser = new QueryParser(Version.LUCENE_4_9, "mod2", analyzer);
        
        // should give us 150 hits.
        Query query = parser.parse("0");
        TopDocs docs = searcher.search(query, 300);
        Assert.assertEquals(150, docs.totalHits);
    } 
    
    @AfterClass 
    public static void clearData() {
        cassandra.session.close();
        cassandra.cluster.close();
        // uses hector. let's not do this.
        //EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
        TestUtil.removeDirOnExit(new File("/tmp/collene"));
    }
    
    private static IndexWriter makeWriter(Directory dir, PrintStream out) throws Exception {
        IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_4_9, analyzer);
        if (out != null) {
            config.setInfoStream(out);
        }
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        IndexWriter writer = new IndexWriter(dir, config);
        return writer;
    }
    
    private void writeDocuments(String keyPrefix, int count, Directory dir) throws Exception {
        IndexWriter writer = makeWriter(dir, null);
        
        Collection<Document> documents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Document doc = new Document();
            doc.add(new Field("key", String.format("%s,%s", keyPrefix, Integer.toHexString(i)), TextField.TYPE_STORED));
            doc.add(new Field("mod2", Integer.toString(i % 2), TextField.TYPE_NOT_STORED));
            doc.add(new Field("mod3", Integer.toString(i % 3), TextField.TYPE_NOT_STORED));
            documents.add(doc);
        }
        writer.addDocuments(documents);
        writer.close(true);
    }
    
    @Parameterized.Parameters
    public static Collection<Object[]> data() throws Exception {
        Collection<Object[]> list = new ArrayList<Object[]>();
        
        Object[] fsDirectory = new Object[]{ 
                FSDirectory.open(TestUtil.getRandomTempDir()), 
                FSDirectory.open(TestUtil.getRandomTempDir()), 
                FSDirectory.open(TestUtil.getRandomTempDir()) 
        };
        list.add(fsDirectory);
        
        CassandraIO baseCassandraIO = new CassandraIO(NextCassandraPrefix.get(), 8192, "collene", "cindex").session(cassandra.session);
        Object[] cassColDirectory = new Object[] {
                ColDirectory.open(NextCassandraPrefix.get(), baseCassandraIO.clone(NextCassandraPrefix.get()), baseCassandraIO.clone(NextCassandraPrefix.get())),
                ColDirectory.open(NextCassandraPrefix.get(), baseCassandraIO.clone(NextCassandraPrefix.get()), baseCassandraIO.clone(NextCassandraPrefix.get())),
                ColDirectory.open(NextCassandraPrefix.get(), baseCassandraIO.clone(NextCassandraPrefix.get()), baseCassandraIO.clone(NextCassandraPrefix.get()))
        };
        list.add(cassColDirectory);
        
        return list;
    }
}