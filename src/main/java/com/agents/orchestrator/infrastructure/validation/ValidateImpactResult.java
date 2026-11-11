package com.agents.orchestrator.infrastructure.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline validator for the published result contract; does not call any provider. */
public final class ValidateImpactResult {
    private ValidateImpactResult() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("EXPECTED_RESULT_FILE");
        var file = Path.of(args[0]);
        if (Files.size(file) > 1_000_000) throw new IllegalArgumentException("RESULT_TOO_LARGE");
        var json = new ObjectMapper();
        try (var input =
                ValidateImpactResult.class.getResourceAsStream("/impact-analysis.schema.json")) {
            var schema =
                    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                            .getSchema(json.readTree(input));
            if (!schema.validate(json.readTree(Files.readString(file))).isEmpty()) {
                throw new IllegalArgumentException("INVALID_IMPACT_RESULT");
            }
        }
        System.out.println("VALID_IMPACT_RESULT");
    }
}
