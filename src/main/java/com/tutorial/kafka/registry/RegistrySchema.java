package com.tutorial.kafka.registry;

public class RegistrySchema {
	public String subject;
	public String version;
	public String id;
	public String schema;
	
	
	@Override
	public String toString() {
		return "RegistrySchema [subject=" + subject + ", version=" + version + ", id=" + id + ", schema=" + schema
				+ "]";
	}
	

}
