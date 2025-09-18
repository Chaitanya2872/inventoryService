package com.bmsedge.inventory.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Custom deserializer for BigDecimal to handle various input formats
 * including empty objects, null values, and invalid data
 */
public class BigDecimalDeserializer extends JsonDeserializer<BigDecimal> {

    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {

        JsonToken token = parser.getCurrentToken();

        // Handle null token
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }

        // Handle empty object {} - return null
        if (token == JsonToken.START_OBJECT) {
            // Skip the entire object
            parser.skipChildren();
            return null;
        }

        // Handle empty array [] - return null
        if (token == JsonToken.START_ARRAY) {
            // Skip the entire array
            parser.skipChildren();
            return null;
        }

        // Handle string values
        if (token == JsonToken.VALUE_STRING) {
            String value = parser.getText();
            if (value == null || value.trim().isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(value.trim());
            } catch (NumberFormatException e) {
                // Invalid number format, return null
                return null;
            }
        }

        // Handle numeric values
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return parser.getDecimalValue();
        }

        // Handle boolean values (true = 1, false = 0)
        if (token == JsonToken.VALUE_TRUE) {
            return BigDecimal.ONE;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return BigDecimal.ZERO;
        }

        // For any other token type, return null
        return null;
    }
}