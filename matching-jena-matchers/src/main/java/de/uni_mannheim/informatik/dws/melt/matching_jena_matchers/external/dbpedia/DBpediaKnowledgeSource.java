package de.uni_mannheim.informatik.dws.melt.matching_jena_matchers.external.dbpedia;

import de.uni_mannheim.informatik.dws.melt.matching_jena_matchers.external.LabelToConceptLinker;
import de.uni_mannheim.informatik.dws.melt.matching_jena_matchers.external.Language;
import de.uni_mannheim.informatik.dws.melt.matching_jena_matchers.external.SemanticWordRelationDictionary;
import de.uni_mannheim.informatik.dws.melt.matching_jena_matchers.external.services.persistence.PersistenceService;
import de.uni_mannheim.informatik.dws.melt.matching_jena_matchers.external.services.sparql.SparqlServices;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.shared.Lock;
import org.apache.jena.tdb.TDBFactory;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static de.uni_mannheim.informatik.dws.melt.matching_jena_matchers.external.services.persistence.PersistenceService.PreconfiguredPersistences.*;

/**
 * DBpedia knowledge source.
 * Works with the online endpoint and TDB 1.
 */
public class DBpediaKnowledgeSource extends SemanticWordRelationDictionary {


    /**
     * Logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(DBpediaKnowledgeSource.class);

    /**
     * The public SPARQL endpoint.
     */
    private static final String ENDPOINT_URL = "https://dbpedia.org/sparql";

    private String name = "DBpedia";

    private DBpediaLinker linker;

    /**
     * Buffer for repeated synonymy requests.
     */
    ConcurrentMap<String, Set<String>> synonymyBuffer;

    /**
     * Buffer for repeated hypernymy requests.
     */
    ConcurrentMap<String, Set<String>> hypernymyBuffer;

    /**
     * DBpedia annotates quite a lot of hypernyms. Some of them may be misleading for some analyses such as
     * "http://www.w3.org/2002/07/owl#Thing".
     */
    private Set<String> excludedHypernyms = new HashSet<>();

    /**
     * Service responsible for disk buffers.
     */
    private PersistenceService persistenceService;

    /**
     * If the disk-buffer is disabled, no buffers are read/written from/to the disk.
     * Default: true.
     */
    private boolean isDiskBufferEnabled = true;

    /**
     * True if a tdb source shall be used rather than an on-line SPARQL endpoint.
     */
    private boolean isUseTdb = false;

    public static String getEndpointUrl() {
        return ENDPOINT_URL;
    }

    /**
     * The TDB dataset into which the DBpedia data set was loaded.
     */
    private Dataset tdbDataset;

    /**
     * TDB model
     */
    private Model tdbModel;

    /**
     * Default constructor. SPARQL endpoint will be queried.
     * Disk buffer is enabled by default.
     */
    public DBpediaKnowledgeSource(){
        this(true);
    }

    /**
     * Constructor for SPARQL access.
     * @param isDiskBufferEnabled True if a disk buffer shall be enabled by default.
     */
    public DBpediaKnowledgeSource(boolean isDiskBufferEnabled){
        this.isDiskBufferEnabled = isDiskBufferEnabled;
        initializeMembers();
    }

    /**
     * Constructor for DBpedia TDB access.
     * @param tdbDirectoryPath The path to the TDB directory.
     */
    public DBpediaKnowledgeSource(String tdbDirectoryPath){
        this(tdbDirectoryPath, true);
    }

    /**
     * Constructor for DBpedia TDB access.
     * @param tdbDirectoryPath The path to the TDB directory.
     * @param isDiskBufferEnabled True if the disk buffer shall be enabled.
     */
    public DBpediaKnowledgeSource(String tdbDirectoryPath, boolean isDiskBufferEnabled){
        // convenience checks for stable code
        File tdbDirectoryFile = new File(tdbDirectoryPath);
        if (!tdbDirectoryFile.exists()) {
            LOGGER.error("tdbDirectoryPath does not exist. - ABORTING PROGRAM");
            return;
        }
        if (!tdbDirectoryFile.isDirectory()) {
            LOGGER.error("tdbDirectoryPath is not a directory. - ABORTING PROGRAM");
            return;
        }
        this.isUseTdb = true;
        tdbDataset = TDBFactory.createDataset(tdbDirectoryPath);
        tdbModel = tdbDataset.getDefaultModel();
        //tdbDataset.begin(ReadWrite.READ);

        this.isDiskBufferEnabled = isDiskBufferEnabled;
        initializeMembers();
    }

    /**
     * Initializations that have to be performed.
     */
    private void initializeMembers(){
        initializeBuffers();
        initializeLinker();
        initializeHypernymExclusion();
    }

    private void initializeHypernymExclusion(){
        // hypernym exclusion default set
        excludedHypernyms.add("http://www.w3.org/2002/07/owl#Thing");
    }

    /**
     * Helper method to initialize the linker.
     */
    private void initializeLinker(){
        if(this.linker == null){
            this.linker = new DBpediaLinker(this);
        }
    }

    /**
     * Initialize buffers (either on-disk or memory).
     */
    private void initializeBuffers(){
        if(isDiskBufferEnabled){
            persistenceService = PersistenceService.getService();
            this.synonymyBuffer = persistenceService.getMapDatabase(DBPEDIA_SYNONYMY_BUFFER);
            this.hypernymyBuffer = persistenceService.getMapDatabase(DBPEDIA_HYPERNYMY_BUFFER);
        } else {
            this.synonymyBuffer = new ConcurrentHashMap<>();
            this.hypernymyBuffer = new ConcurrentHashMap<>();
        }
    }

    public boolean isInDictionary(String word) {
        return linker.linkToSingleConcept(word, Language.ENGLISH) != null;
    }

    /**
     * Checks for hypernymous words in a loose-form fashion: One concept needs to be a hypernym of the other concept
     * where the order of concepts is irrelevant, i.e., the method returns (hypernymous(w1, w2) || hypernymous(w2, w1).
     *
     * The assumed language is English.
     * CHECKS ONLY FOR LEVEL 1 HYPERNYMY - NO REASONING IS PERFORMED.
     *
     * @param linkedConcept_1 linked word 1
     * @param linkedConcept_2 linked word 2
     * @return True if the given words are hypernymous, else false.
     */
    @Override
    public boolean isHypernymous(String linkedConcept_1, String linkedConcept_2){
        if(linkedConcept_1 == null || linkedConcept_2 == null) {
            return false;
        }

        Set<String> hypernymsTmp_1 = getHypernyms(linkedConcept_1);
        Set<String> hypernymsTmp_2 = getHypernyms(linkedConcept_2);
        Set<String> hypernyms_1 = new HashSet<>();
        Set<String> hypernyms_2 = new HashSet<>();

        for(String hypernymLink : hypernymsTmp_1){
            if(this.linker.isMultiConceptLink(hypernymLink)){
                hypernyms_1.addAll(this.linker.getUris(hypernymLink));
            } else {
                hypernyms_1.add(hypernymLink);
            }
        }

        for(String hypernymLink : hypernymsTmp_2){
            if(this.linker.isMultiConceptLink(hypernymLink)){
                hypernyms_2.addAll(this.linker.getUris(hypernymLink));
            } else {
                hypernyms_2.add(hypernymLink);
            }
        }


        if(this.linker.isMultiConceptLink(linkedConcept_1)){
            for(String uri1 : this.linker.getUris(linkedConcept_1)){
                if(hypernyms_2.contains(uri1)){
                    return true;
                }
            }
        } else {
            if(hypernyms_2.contains(linkedConcept_1)){
                return true;
            }
        }

        if(this.linker.isMultiConceptLink(linkedConcept_2)){
            for(String uri2 : this.linker.getUris(linkedConcept_2)){
                if(hypernyms_1.contains(uri2)){
                    return true;
                }
            }
        } else {
            if(hypernyms_1.contains(linkedConcept_2)){
                return true;
            }
        }

        return false;
    }

    @Override
    @NotNull
    public Set<String> getSynonymsLexical(String linkedConcept) {
        Set<String> result = new HashSet<>();
        if(linkedConcept == null || linkedConcept.equals("")){
            return result;
        }
        String key = linkedConcept + "_syns_lexical";
        if (synonymyBuffer.containsKey(key)) {
            return synonymyBuffer.get(key);
        }
        String queryString = getSynonymsLexicalQuery(linkedConcept);
        QueryExecution queryExecution;
        if(isUseTdb){
            tdbModel.enterCriticalSection(Lock.READ);
            queryExecution = QueryExecutionFactory.create(queryString, tdbModel);
        } else {
            queryExecution = QueryExecutionFactory.sparqlService(getEndpointUrl(), queryString);
        }
        ResultSet resultSet = SparqlServices.safeExecution(queryExecution);
        while(resultSet.hasNext()){
            QuerySolution solution = resultSet.next();
            String label = solution.getLiteral("l").getLexicalForm();
            result.add(label);
        }
        queryExecution.close();
        if(isUseTdb){
            tdbModel.leaveCriticalSection();
        }
        result.remove("");
        synonymyBuffer.put(key, result);
        commitAll();
        return result;
    }

    /**
     * Checks for synonymy by determining whether link1 is contained in the set of synonymous words of link2 or
     * vice versa.
     * @param link1 Word 1
     * @param link2 Word 2
     * @return True if the given words are synonymous, else false.
     */
    @Override
    public boolean isStrongFormSynonymous(String link1, String link2){
        if(link1 == null || link2 == null) {
            return false;
        }

        Set<String> uris1 = new HashSet<>();
        Set<String> uris2 = new HashSet<>();

        if(this.linker.isMultiConceptLink(link1)){
            uris1.addAll(this.linker.getUris(link1));
        } else {
            uris1.add(link1);
        }

        if(this.linker.isMultiConceptLink(link2)){
            uris2.addAll(this.linker.getUris(link2));
        } else {
            uris2.add(link2);
        }

        for(String uri : uris1){
            if(uris2.contains(uri)){
                return true;
            }
        }

        return false;
    }

    /**
     * Builds a String query to obtain synonyms. The synonyms are represented by normal words/labels (not URIs).
     * @param link The link for which synonymous words shall be obtained.
     * @return A SPARQL query as String.
     */
    String getSynonymsLexicalQuery(String link) {
        Set<String> uris = linker.getUris(link);
        StringBuilder result = new StringBuilder();
        result.append("SELECT DISTINCT ?l WHERE {\n");
        boolean first = true;
        for(String uri : uris) {
            if (first) {
                first = false;
            } else {
                result.append("UNION ");
            }
            result
                    .append(getSubjectPredicateQueryLineForLabels(uri, "http://www.w3.org/2000/01/rdf-schema#label"))
                    .append("UNION ")
                    .append(getSubjectPredicateQueryLineForLabels(uri, "http://xmlns.com/foaf/0.1/name"))
                    .append("UNION ")
                    .append(getSubjectPredicateQueryLineForLabels(uri, "http://dbpedia.org/property/name"))
                    .append("UNION ")
                    .append(getSubjectPredicateQueryLineForLabels(uri, "http://dbpedia.org/property/otherNames"))
                    .append("UNION ")
                    .append(getSubjectPredicateQueryLineForLabels(uri, "http://dbpedia.org/ontology/alias"));
        }
        result.append("}");
        return result.toString();
    }

    /**
     * Helper method to build a query.
     *
     * @param subject   The subject
     * @param predicate The predicate.
     * @return A string builder.
     */
    static StringBuilder getSubjectPredicateQueryLineForLabels(String subject, String predicate) {
        StringBuilder result = new StringBuilder();
        result.append("{<")
                .append(subject)
                .append("> <")
                .append(predicate)
                .append("> ?l }\n");
        return result;
    }

    @Override
    @NotNull
    public Set<String> getHypernyms(String linkedConcept) {
        Set<String> result = new HashSet<>();
        if(linkedConcept == null){
            return result;
        }
        String key = linkedConcept;
        if(hypernymyBuffer.containsKey(key)){
            // we now need to remove the exclusion concepts:
            result.addAll(hypernymyBuffer.get(key));
            result.removeAll(getExcludedHypernyms());
            return result;
        }
        String queryString = getHypernymsQuery(linkedConcept);
        QueryExecution queryExecution;

        if(isUseTdb){
            tdbModel.enterCriticalSection(Lock.READ);
            queryExecution = QueryExecutionFactory.create(queryString, tdbModel);
        } else {
            queryExecution = QueryExecutionFactory.sparqlService(getEndpointUrl(), queryString);
        }

        ResultSet queryResult = SparqlServices.safeExecution(queryExecution);
        while(queryResult.hasNext()){
            QuerySolution solution = queryResult.next();
            String hypernym = solution.getResource("c").getURI();
            result.add(hypernym);
        }
        queryExecution.close();
        if(isUseTdb){
            tdbModel.leaveCriticalSection();
        }

        // we add to the buffer before excluding hypernyms
        hypernymyBuffer.put(key, result);
        commitAll();

        result.removeAll(getExcludedHypernyms());
        return result;
    }

    /**
     * Construct a query for hypernyms.
     * @param linkedConcept The concept for which hypernyms shall be retrieved.
     * @return SPARQL query as String.
     */
    private String getHypernymsQuery(String linkedConcept){
        StringBuilder result = new StringBuilder();
        Set<String> uris = linker.getUris(linkedConcept);
        result.append("SELECT DISTINCT ?c WHERE {\n");
        boolean first = true;
        for(String uri : uris){
            if (first) {
                first = false;
            } else {
                result.append("UNION ");
            }
            result.append(getSubjectPredicateQueryLineForConcepts(uri, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"));
            result.append("UNION ");
            result.append(getSubjectPredicateQueryLineForConcepts(uri, "http://dbpedia.org/ontology/type"));
        }
        result.append("}");
        return result.toString();
    }

    /**
     * Helper method to build a query.
     *
     * @param subject   The subject
     * @param predicate The predicate.
     * @return A string builder.
     */
    static StringBuilder getSubjectPredicateQueryLineForConcepts(String subject, String predicate) {
        StringBuilder result = new StringBuilder();
        result.append("{<")
                .append(subject)
                .append("> <")
                .append(predicate)
                .append("> ?c }\n");
        return result;
    }

    /**
     * Commit data changes if active.
     */
    private void commitAll(){
        if(isDiskBufferEnabled && this.persistenceService != null) {
            persistenceService.commit(DBPEDIA_SYNONYMY_BUFFER);
            persistenceService.commit(DBPEDIA_HYPERNYMY_BUFFER);
        }
    }

    @Override
    public void close() {
        commitAll();
        if(tdbDataset != null) {
            tdbDataset.end();
            tdbDataset.close();
            LOGGER.info("DBpedia TDB dataset closed.");
        }
    }

    @Override
    public LabelToConceptLinker getLinker() {
        return this.linker;
    }

    public boolean isUseTdb() {
        return isUseTdb;
    }

    public Dataset getTdbDataset() {
        return tdbDataset;
    }

    @Override
    public String getName() {
        return name;
    }

    public Set<String> getExcludedHypernyms() {
        return excludedHypernyms;
    }

    public boolean isDiskBufferEnabled() {
        return isDiskBufferEnabled;
    }

    public void setExcludedHypernyms(Set<String> excludedHypernyms) {
        this.excludedHypernyms = excludedHypernyms;
    }
}
