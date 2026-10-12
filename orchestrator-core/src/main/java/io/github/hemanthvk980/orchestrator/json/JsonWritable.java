package io.github.hemanthvk980.orchestrator.json;

/** Implemented by domain records that serialize themselves into the audit log and run reports. */
public interface JsonWritable {
    Object toJson();
}
