/*
 * Copyright 2014 Gary Dusbabek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package collene;

import freedb.FreeDbEntry;
import freedb.FreeDbReader;
import com.google.common.base.Charsets;
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
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.util.Version;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

public class Freedb {
    
    private static final int MAX_ENTRIES = Integer.MAX_VALUE;
    private static final int BATCH_SIZE = 10;
    private static final boolean VERBOSE = false;
    private static PrintStream out = System.out;
    private static final String name = "index_freedb_cass_test";
    private static final String metaName = "meta_freedb_cass_test";
    
    public static void main(String args[]) throws Exception {
        //dumpGenres(args);
        //System.exit(0);
        
        if (args.length == 0) {
            System.out.println("Need to specify: { memory | file | cassandra }");
            System.exit(-1);
        }
        
        Callable<Directory> directoryBuilder = buildDirectory(args[0]);
        
        try {
            BuildIndex(directoryBuilder.call());
        } catch (LockObtainFailedException ex) {
            ex.printStackTrace(out);
            System.exit(-1);
        }
        out.println("\nWill now do an independent search\n");
        DoSearch(directoryBuilder.call());
        out.println("\nWill now do an independent search\n");
        DoSearch(directoryBuilder.call());
        out.println("\nWill now do an independent search\n");
        DoSearch(directoryBuilder.call());
        
        System.exit(0);
    }
    
    public static void dumpGenres(String args[]) throws Exception {
        out.println("dumping genres for fun");
        String freedbPath = "/Users/gdusbabek/Downloads/freedb-complete-20140701.tar.bz2";
        FreeDbReader reader = new FreeDbReader(new File(freedbPath), 50000);
        reader.start();
        Set<String> genres = new HashSet<String>();
        FreeDbEntry entry = reader.next();
        while (entry != null) {
            if (entry.getGenre() != null && entry.getGenre().length() > 0)
                genres.add(entry.getGenre().toLowerCase());
            entry = reader.next();
        }
        OutputStream out = new FileOutputStream("/tmp/genres.txt");
        for (String genre : genres) {
            try {
                out.write(genre.getBytes(Charsets.UTF_8));
                out.write('\n');
            } catch (Throwable th) {
                System.out.println(th.getMessage());
            }
        }
        out.close();
    }
    
    public static void DoSearch(Directory directory) throws Exception {
        
        out.println("I think these are the files:");
        for (String s : directory.listAll()) {
            out.println(s);
        }
        
        IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(directory));
        Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_4_9);
        QueryParser parser = new QueryParser(Version.LUCENE_4_9, "any", analyzer);
        for (int i = 0; i < 5; i++) {
            long searchStart = System.currentTimeMillis();
            Query query = parser.parse("morrissey");
            //Query query = parser.parse("Dance");
            TopDocs docs = searcher.search(query, 10);
            long searchEnd = System.currentTimeMillis();
            out.println(String.format("%s %d total hits in %d", directory.getClass().getSimpleName(), docs.totalHits, searchEnd - searchStart));
            long lookupStart = System.currentTimeMillis();
            for (ScoreDoc d : docs.scoreDocs) {
                Document doc = searcher.doc(d.doc);
                out.println(String.format("%d %.2f %d %s", d.doc, d.score, d.shardIndex, doc.getField("any").stringValue()));
            }
            long lookupEnd = System.currentTimeMillis();
            out.println(String.format("Document lookup took %d ms for %d documents", lookupEnd-lookupStart, docs.scoreDocs.length));
        }
        
        directory.close();
    }
    
    private static Callable<Directory> buildDirectory(final String type) throws IOException {
        return new Callable<Directory>() {
            
            private final CassandraIO baseIO = new CassandraIO(name, 8192, "collene", "cindex").start("127.0.0.1:9042");
            private final File tmpDir = TestUtil.getRandomTempDir();
            private final MemoryIO dataMemory = new MemoryIO(8192);
            private final MemoryIO metaMemory = new MemoryIO(256);
            
            @Override
            public Directory call() throws Exception {
                Directory directory = null;
                
                if ("cassandra".equals(type)) {
                    directory = ColDirectory.open(
                            name,
                            baseIO,
                            baseIO.clone(metaName)
                    );
                } else if ("file".equals(type)) {
                    System.out.println("Building index in " + tmpDir.getAbsolutePath());
                    directory = FSDirectory.open(tmpDir);
                } else if ("memory".equals(type)) {
                    directory = ColDirectory.open(
                            name,
                            dataMemory,
                            metaMemory
                    );
                } else {
                    directory = null;
                }   
                return directory;
            }
        };
    }
    
    public static void BuildIndex(Directory directory) throws Exception {
        String freedbPath = "/Users/gdusbabek/Downloads/freedb-complete-20140701.tar.bz2";
        
        if (directory == null) {
            System.out.println("Need to specify: { memory | file | cassandra }. Did you misspell something?");
            System.exit(-1);
        }

        FreeDbReader reader = new FreeDbReader(new File(freedbPath), 50000);
        reader.start();

        long indexStart = System.currentTimeMillis();
        Collection<Document> documents = new ArrayList<Document>(BATCH_SIZE);
        Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_4_9);
        IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_4_9, analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        IndexWriter writer = new IndexWriter(directory, config);
        
        // stop after this many documents.
        final int maxDocuments = 400000; //Integer.MAX_VALUE;

        FreeDbEntry entry = reader.next();
        int count = 0;
        while (entry != null && count < maxDocuments) {
            Document doc = new Document();
            String any = entry.toString();
            doc.add(new Field("any", any, TextField.TYPE_STORED));
            doc.add(new Field("artist", entry.getArtist(), TextField.TYPE_NOT_STORED));
            doc.add(new Field("album", entry.getAlbum(), TextField.TYPE_NOT_STORED));
            doc.add(new Field("title", entry.getTitle(), TextField.TYPE_NOT_STORED));
            doc.add(new Field("genre", entry.getGenre(), TextField.TYPE_NOT_STORED));
            doc.add(new Field("year", entry.getYear(), TextField.TYPE_NOT_STORED));
            for (int i = 0; i < entry.getTrackCount(); i++) {
                doc.add(new Field("track", entry.getTrack(i), TextField.TYPE_STORED));
            }
            documents.add(doc);
            if (VERBOSE) {
                out.println(any);
            }
            
            if (documents.size() == BATCH_SIZE) {
                //out.println(String.format("Adding batch at count %d", count));
                writer.addDocuments(documents);
                //out.println("done");
                documents.clear();
            }
            
            count +=1;
            if (count >= MAX_ENTRIES) {
                // done indexing.
                break;
            }
            entry = reader.next();
            
            if (count % 100000 == 0) {
                out.println(String.format("Indexed %d documents", count));
                
                // do a quick morrissey search for fun.
//                IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(ColDirectory.open(
//                                new CassandraIO(8192, "collene", "cindex").start("127.0.0.1:9042"),
//                                new CassandraIO(8192, "collene", "cmeta").start("127.0.0.1:9042"),
//                                new CassandraIO(8192, "collene", "clock").start("127.0.0.1:9042")
//                )));
                IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(writer, false));
                QueryParser parser = new QueryParser(Version.LUCENE_4_9, "any", analyzer);
                long searchStart = System.currentTimeMillis();
                Query query = parser.parse("morrissey");
                TopDocs docs = searcher.search(query, 10);
                long searchEnd = System.currentTimeMillis();
                out.println(String.format("%s %d total hits in %d", directory.getClass().getSimpleName(), docs.totalHits, searchEnd - searchStart));
                for (ScoreDoc d : docs.scoreDocs) {
                    out.println(String.format("%d %.2f %d", d.doc, d.score, d.shardIndex));
                }
            }
        }
        
        if (documents.size() > 0) {
            out.println(String.format("Adding batch at count %d", count));
            writer.addDocuments(documents);
            out.println("done");
            documents.clear();
            
            // do a quick morrissey search for fun.
            IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(writer, false));
            QueryParser parser = new QueryParser(Version.LUCENE_4_9, "any", analyzer);
            long searchStart = System.currentTimeMillis();
            Query query = parser.parse("morrissey");
            TopDocs docs = searcher.search(query, 10);
            long searchEnd = System.currentTimeMillis();
            out.println(String.format("%s %d total hits in %d", directory.getClass().getSimpleName(), docs.totalHits, searchEnd - searchStart));
            for (ScoreDoc d : docs.scoreDocs) {
                out.println(String.format("%d %.2f %d", d.doc, d.score, d.shardIndex));
            }
        }
        
        long indexTime = System.currentTimeMillis() - indexStart;
        out.println(String.format("Indexed %d things in %d ms (%s)", count, indexTime, directory.toString()));
        
//        long startMerge = System.currentTimeMillis();
//        writer.forceMerge(1, true);
//        long endMerge = System.currentTimeMillis();
//        out.println(String.format("merge took %d ms", endMerge-startMerge));
        out.println("I think these are the files:");
        for (String s : directory.listAll()) {
            out.println(s);
        }
        
        writer.close(true);
        directory.close();
    }
}
