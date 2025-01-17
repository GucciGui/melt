package de.uni_mannheim.informatik.dws.melt.matching_validation.trackspecific;

import java.util.stream.Stream;

import de.uni_mannheim.informatik.dws.melt.matching_data.TrackRepository;
import org.junit.jupiter.api.DynamicTest;

public class TestKnowledgeGraphTrack {


    /*
    @Test
    public void analyze() {
        AssertHelper.assertTrack(TrackRepository.Knowledgegraph.V3);
    }
    */
    
    
    //@TestFactory //should not be executed every time someone pushes to the github repro
    Stream<DynamicTest> analyze() {
        return AssertHelper.assertDynamicTrack(TrackRepository.Knowledgegraph.V3);
    }
}
