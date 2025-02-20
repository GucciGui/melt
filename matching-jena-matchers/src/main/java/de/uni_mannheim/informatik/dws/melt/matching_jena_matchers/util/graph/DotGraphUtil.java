package de.uni_mannheim.informatik.dws.melt.matching_jena_matchers.util.graph;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Util to write Dot graphs.
 * The resulting file can be tranformed to a grapg with the command
 * <code>dot -Tpng -o out.png file.dot</code>
 */
public class DotGraphUtil {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(DotGraphUtil.class);
    
    public static <T> void writeDirectedGraphToDotFile(File dotFile, List<Entry<T, T>> edges, Function<T, String> nodeToId, String... graphAttributes){
        Map<T, Set<T>> e = new HashMap();
        for(Entry<T, T> edge : edges){
            e.computeIfAbsent(edge.getKey(), __->new HashSet()).add(edge.getValue());
        }
        writeDirectedGraphToDotFile(dotFile, e, nodeToId, graphAttributes);
    }
    
    public static <T> void writeDirectedGraphToDotFile(File dotFile, List<Entry<T, T>> edges){
        writeDirectedGraphToDotFile(dotFile, edges, x -> makeQuotedNodeID(x.toString()));
    }
    
    public static <T> void writeDirectedGraphToDotFile(File dotFile, Map<T, Set<T>> edges, Function<T, String> nodeToId, String... graphAttributes){
        try(BufferedWriter w = new BufferedWriter(new FileWriter(dotFile))){
            w.write("digraph D {");
            w.newLine();
            for(String graphAttr : graphAttributes){
                w.write(graphAttr);
                w.newLine();
            }
            for(Entry<T, Set<T>> edge : edges.entrySet()){
                String source = nodeToId.apply(edge.getKey());
                if(source == null)
                    continue;
                for(T target : edge.getValue()){
                    String targetStr = nodeToId.apply(target);
                    if(targetStr == null)
                        continue;
                    w.write("    " + source + " -> " + targetStr + ";");
                    w.newLine();
                }
            }
            w.write("}");
        } catch (IOException ex) {
            LOGGER.warn("Could not write dot file.", ex);
        }
    }
    
    public static <T> void writeDirectedGraphToDotFile(File dotFile, Map<T, Set<T>> edges){
        writeDirectedGraphToDotFile(dotFile, edges, x -> makeQuotedNodeID(x.toString()));
    }
        
    public static String makeQuotedNodeID(String nodeId){
        //not perfect but should work for most cases
        //in the best case look if the quotation mark should be esacped by looking at an even number of backslahes before.
        if(StringUtils.isBlank(nodeId)){
            return null;
        }
        return "\"" + nodeId.replace("\\", "\\\\" ).replace("\"", "\\\"" ) + "\""; 
    }
}
