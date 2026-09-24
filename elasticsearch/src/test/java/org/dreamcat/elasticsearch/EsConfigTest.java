package org.dreamcat.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class EsConfigTest {

    @Test
    void matchesRepeatedIndexParametersWithOnlyPercentAsWildcard() throws Exception {
        EsConfig config = EsConfig.parse("jdbc:es7://localhost:9200?index=some%index&index=some_index2", null);
        assertTrue(config.allowsIndex("someindex"));
        assertTrue(config.allowsIndex("some-long-index"));
        assertTrue(config.allowsIndex("some_index2"));
        assertFalse(config.allowsIndex("someXindex2"));
        assertFalse(config.allowsIndex("other_index"));

        EsConfig encoded = EsConfig.parse("jdbc:es7://localhost:9200?index=%25index%25", null);
        assertTrue(encoded.allowsIndex("my-index-name"));
        assertFalse(encoded.allowsIndex("other"));
        assertTrue(EsConfig.parse("jdbc:es7://localhost:9200", null).allowsIndex("anything"));
        assertThrows(SQLException.class, () -> EsConfig.parse("jdbc:es7://localhost:9200?index=", null));
    }
}
