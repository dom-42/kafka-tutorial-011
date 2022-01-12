package com.tutorial.kafka;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tutorial.kafka.registry.RegistrySchema;

@SpringBootApplication
public class KafkaTutorial011Application implements CommandLineRunner {

	private static final Logger logger = LoggerFactory.getLogger(KafkaTutorial011Application.class);


	public static void main(String[] args) {
		SpringApplication.run(KafkaTutorial011Application.class, args);
	}

	@Override
	public void run(String... args) throws Exception {

		WebClient registryClient = WebClient.create();

		WebClient.ResponseSpec responseSpec = registryClient
				.get()
				.uri("http://localhost:8081/schemas")
				.retrieve();

		RegistrySchema[] registrySchemas = responseSpec.bodyToMono(RegistrySchema[].class).block();

		String partitionBy="";
		String createStreamSTD="";
		String createStreamRAW="";
		for (RegistrySchema registrySchema:registrySchemas) {



			String subject = new JsonParser().parse(registrySchema.subject).getAsString();
			if (subject.startsWith("CAI.") || subject.startsWith("COM.")) {
				JsonObject schema = new JsonParser().parse(registrySchema.schema).getAsJsonObject();
				logger.info("{}",subject);
				//logger.info(".{}",(schema.get("fields")).getAsJsonArray().toString());


				if (subject.endsWith("key")) {
					partitionBy="";
					partitionBy+="PARTITION BY \n";
					for (JsonElement jsonElement:(schema.get("fields")).getAsJsonArray()) {
						//logger.info("......> {}", standardizeColumn(jsonElement.getAsJsonObject().get("name").toString()));		
						partitionBy+=String.format("	%s ,\n",standardizeColumn(jsonElement.getAsJsonObject().get("name").toString(), false));
					}
					partitionBy = partitionBy.replaceAll(".$", ";");
				}


				if (subject.endsWith("value")) {
					createStreamRAW="";
					createStreamRAW+=String.format("CREATE STREAM %s_RAW_S01 WITH (KAFKA_TOPIC='%s', VALUE_FORMAT='AVRO', KEY_FORMAT='AVRO' );\n", subject.replaceAll("-value$", "").replace('.', '_'), subject.replaceAll("-value$", ""));
					createStreamSTD="";
					createStreamSTD+=String.format("CREATE STREAM %s_STD_S01  AS SELECT \n", subject.replaceAll("-value$", "").replace('.', '_'));
					String streamFields="";
					for (JsonElement jsonElement:(schema.get("fields")).getAsJsonArray()) {
						try {
							if (jsonElement.getAsJsonObject().get("type").getAsJsonObject().get("name").getAsString().equals("Data")) {
								//logger.info("... {}", jsonElement.getAsJsonObject().get("type").getAsJsonObject().get("name"));
								for (JsonElement col:(jsonElement.getAsJsonObject().get("type").getAsJsonObject().get("fields").getAsJsonArray())) {
									//logger.info("......> {}", standardizeColumn(col.getAsJsonObject().get("name").toString()));
									createStreamSTD+=String.format("	%s ,\n",standardizeColumn(col.getAsJsonObject().get("name").toString(), true));
								}
								createStreamSTD+="	HEADERS->OPERATION AS \"operation\",\n"
										+ "	ROWTIME AS \"eventTS\",\n"
										+ "	CASE\n"
										+ "	    WHEN TRIM(HEADERS->CHANGESEQUENCE) = '' THEN ROWTIME\n"
										+ "	    ELSE STRINGTOTIMESTAMP(SUBSTRING(HEADERS->CHANGESEQUENCE,1,14), 'yyyyMMddHHmmss')\n"
										+ "	END AS \"sourceTS\",\n"
										+ "	PARSE_DATE(\n"
										+ "	CASE\n"
										+ "	    WHEN TRIM(HEADERS->CHANGESEQUENCE) = '' THEN TIMESTAMPTOSTRING(ROWTIME, 'yyyyMMdd')\n"
										+ "	    ELSE SUBSTRING(HEADERS->CHANGESEQUENCE,1,8)\n"
										+ "	END, 'yyyyMMdd') AS \"sourceDate\"\n";
								createStreamSTD+=String.format("FROM %s_RAW_S01 \n",subject.replaceAll("-value$", "").replace('.', '_'));
							}
						}
						catch (IllegalStateException e) {
						}
					}
					logger.info(createStreamRAW);


					// RAW
					Map<String, String> bodyMap = new HashMap();
					bodyMap.put("ksql",createStreamRAW);
					WebClient ksqlClient = WebClient.builder()
							.baseUrl("http://localhost:8088")
							.build();
					String rawResponse = ksqlClient.post()
							.uri("/ksql")
							.body(BodyInserters.fromValue(bodyMap))
							.exchange()
							.flatMap(clientResponse -> {
								if (clientResponse.statusCode().is5xxServerError()) {
									clientResponse.body((clientHttpResponse, context) -> {
										return clientHttpResponse.getBody();
									});
									return clientResponse.bodyToMono(String.class);
								}
								else
									return clientResponse.bodyToMono(String.class);
							})
							.block();
					logger.info("-> rawResponse: {}",rawResponse);

					//STD
					bodyMap.put("ksql",createStreamSTD+partitionBy);
					String stdResponse = ksqlClient.post()
							.uri("/ksql")
							.body(BodyInserters.fromValue(bodyMap))
							.exchange()
							.flatMap(clientResponse -> {
								if (clientResponse.statusCode().is5xxServerError()) {
									clientResponse.body((clientHttpResponse, context) -> {
										return clientHttpResponse.getBody();
									});
									return clientResponse.bodyToMono(String.class);
								}
								else
									return clientResponse.bodyToMono(String.class);
							})
							.block();
					logger.info("-> stdResponse: {}",stdResponse);

					logger.info(createStreamSTD+partitionBy);
				}
			}

		}

	}

	private String standardizeColumn(String rawColumn, boolean select) {
		String parseDate = "PARSE_DATE(CAST(CASE WHEN %s < 19000101 THEN 19000101 ELSE CAST(%s AS INTEGER) END AS VARCHAR), 'yyyyMMdd')";
		String stdColumn = null;
		String alias = null;
		boolean isDate = false;
		stdColumn = rawColumn.replace("\"", "");
		alias = stdColumn;
		if (stdColumn.startsWith("DT_")) isDate=true;
		stdColumn = "DATA->".concat(stdColumn);

		if (isDate) {
			stdColumn=String.format(parseDate, stdColumn, stdColumn);
		}
		if (select) stdColumn = stdColumn.concat(" ").concat(alias);
		return stdColumn;
	}
}
