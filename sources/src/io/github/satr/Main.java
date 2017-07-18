package io.github.satr;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lexmodelbuilding.AmazonLexModelBuilding;
import com.amazonaws.services.lexmodelbuilding.AmazonLexModelBuildingClientBuilder;
import com.amazonaws.services.lexmodelbuilding.model.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

public class Main {

    private static final String LATEST_VERSION = "$LATEST";

    public static void main(String[] args) {
        AmazonLexModelBuilding client = null;
        try {
            AWSCredentials awsCreds = new BasicAWSCredentials("",//IAM user's ACCESS_KEY
                                                              "");//IAM user's SECRET_KEY
            client = AmazonLexModelBuildingClientBuilder.standard()
                                            .withRegion(Regions.US_EAST_1)
                                            .withCredentials(new AWSStaticCredentialsProvider(awsCreds))
                                            .build();

            String ticketTypeSlotTypeName = "TicketType";
            ArrayList<EnumerationValue> ticketSlotTypeValues = new ArrayList<>();
            ticketSlotTypeValues.add(new EnumerationValue().withValue("single"));
            ticketSlotTypeValues.add(new EnumerationValue().withValue("family"));
            ticketSlotTypeValues.add(new EnumerationValue().withValue("monthly"));

            createSlotType(client, ticketTypeSlotTypeName, ticketSlotTypeValues);

            String orderTicketIntentName = "OrderTicket";
            ArrayList<String> sampleUtterances = new ArrayList<>();
            sampleUtterances.add("I would like to order a ticket");
            sampleUtterances.add("I need a ticket please");
            sampleUtterances.add("I need a {TicketType} ticket please");
            sampleUtterances.add("I need {Amount} {TicketType} tickets please");
            sampleUtterances.add("Can I buy a ticket please");

            FulfillmentActivity fulfillmentActivity = new FulfillmentActivity().withType(FulfillmentActivityType.ReturnIntent);

            ArrayList<Slot> slots = new ArrayList<>();
            slots.add(createSlot("TicketType", ticketTypeSlotTypeName, 3,
                                "What type of the ticket?", SlotConstraint.Required));
            slots.add(createSlot("Amount", "AMAZON.NUMBER", 3,
                                "How many?", SlotConstraint.Optional));
            Prompt confirmationPrompt = new Prompt()
                                            .withMaxAttempts(3)
                                            .withMessages(new Message()
                                                                .withContentType(ContentType.PlainText)
                                                                .withContent("Are you ready to complete?"));
            Statement rejectionStatement = new Statement()
                                                .withMessages(new Message()
                                                                .withContentType(ContentType.PlainText)
                                                                .withContent("What else would you like?"));
            createOrUpdateIntent(client, orderTicketIntentName, sampleUtterances, fulfillmentActivity, slots,
                                    confirmationPrompt, rejectionStatement);

            String botName = "OrderBusTicketBot";
            String botDescription = "The bot for ordering tickets";
            String aliasName = "OrderBusTicketBotAlias";

            Statement abortStatement = new Statement()
                                            .withMessages(new Message()
                                                            .withContentType(ContentType.PlainText)
                                                            .withContent("I'm sorry, I cannot understand this."));
            Prompt clarificationPrompt = new Prompt()
                                            .withMaxAttempts(5)
                                            .withMessages(new Message()
                                                            .withContentType(ContentType.PlainText)
                                                            .withContent("Could you please repeat this?"));

            List<String> intentNames = Arrays.asList(orderTicketIntentName);
            String voiceId = "Salli";//Available voices http://docs.aws.amazon.com/polly/latest/dg/voicelist.html
            ProcessBehavior processBehavior = ProcessBehavior.SAVE;//ProcessBehavior.BUILD - to save and then build the bot

            createBot(client, botName, botDescription, voiceId, intentNames, processBehavior, clarificationPrompt, abortStatement);

            createBotAlias(client, botName, aliasName);

//            deleteBot(client, botName);//if this will be run immediately after previous methods - most likely it will raise an error:
                                        // "com.amazonaws.services.lexmodelbuilding.model.ResourceInUseException..."
                                        // this is due to previous create/update operations are in progress

//            showBots(client);
//            showIntents(client);
//            showSlotTypes(client);

        } finally {
            if(client != null)
                client.shutdown();
        }

    }

    private static Slot createSlot(String slotName, String slotType, int maxAttempts, String messageContent, SlotConstraint slotConstraint) {
        Prompt valueElicitationPrompt = new Prompt().withMaxAttempts(maxAttempts)
                                                    .withMessages(new Message().withContentType(ContentType.PlainText)
                                                                                .withContent(messageContent));
        Slot slot = new Slot()
                        .withName(slotName)
                        .withSlotType(slotType)
                        .withSlotConstraint(slotConstraint)
                        .withValueElicitationPrompt(valueElicitationPrompt);
        if(!slotType.startsWith("AMAZON."))
            slot.withSlotTypeVersion(LATEST_VERSION);
        return slot;
    }

    private static Boolean createSlotType(AmazonLexModelBuilding client, String slotTypeName, ArrayList<EnumerationValue> values) {
        return performRequest(slotTypeName, () -> {
            String checksum = getSlotTypeChecksum(client, slotTypeName);
            PutSlotTypeRequest request = new PutSlotTypeRequest()
                                            .withName(slotTypeName)
                                            .withChecksum(checksum)
                                            .withEnumerationValues(values);

            PutSlotTypeResult result = client.putSlotType(request);
            System.out.println(String.format("The slot type \"%s\" has been %s. Checksum: %s",
                    slotTypeName, checksum == null ? "created" : "updated", result.getChecksum()));
            return true;
        });
    }

    private static String getSlotTypeChecksum(AmazonLexModelBuilding client, String slotTypeName) {
        if(!isSlotTypeExist(client, slotTypeName))
            return client.getSlotType(new GetSlotTypeRequest()
                                                .withName(slotTypeName)
                                                .withVersion(LATEST_VERSION)).getChecksum();
        return null;
    }

    private static boolean isSlotTypeExist(AmazonLexModelBuilding client, String slotTypeName) {
        for(SlotTypeMetadata slotTypeMetadata: client.getSlotTypes(new GetSlotTypesRequest()).getSlotTypes()){
            if(slotTypeMetadata.getName().equals(slotTypeName))
                return true;
        }
        return false;
    }

    private static Boolean createOrUpdateIntent(AmazonLexModelBuilding client, String intentName,
                                                ArrayList<String> sampleUtterances, FulfillmentActivity fulfillmentActivity,
                                                ArrayList<Slot> slots, Prompt confirmationPrompt, Statement rejectionStatement) {
        return performRequest(intentName, () -> {
            String checksum = getIntentChecksum(client, intentName);
            System.out.println(String.format("Intent checksum: %s", checksum));
            PutIntentRequest request = new PutIntentRequest()
                                            .withName(intentName)
                                            .withChecksum(checksum)
                                            .withSampleUtterances(sampleUtterances)
                                            .withSlots(slots)
                                            .withFulfillmentActivity(fulfillmentActivity)
                                            .withConfirmationPrompt(confirmationPrompt)
                                            .withRejectionStatement(rejectionStatement);

            PutIntentResult result = client.putIntent(request);

            System.out.println(String.format("The intent \"%s\" has been %s. Checksum: %s.",
                                            checksum == null ? "created" : "updated", intentName, result.getChecksum()));
            return true;
        });
    }

    private static String getIntentChecksum(AmazonLexModelBuilding client, String intentName) {
        if(isIntentExist(client, intentName)){
            return client.getIntent(new GetIntentRequest().withName(intentName).withVersion(LATEST_VERSION))
                         .getChecksum();
        }
        return null;
    }

    private static boolean isIntentExist(AmazonLexModelBuilding client, String intentName) {
        for(IntentMetadata intentMetadata: client.getIntents(new GetIntentsRequest()).getIntents()){
            if(intentMetadata.getName().equals(intentName))
                return true;
        }
        return false;
    }

    private static Boolean createBotAlias(AmazonLexModelBuilding client, String botName, String aliasName) {
        return performRequest(aliasName, () -> {
            String checksum = getBotAliasChecksum(client, botName, aliasName);
            PutBotAliasRequest request = new PutBotAliasRequest()
                                            .withBotName(botName)
                                            .withBotVersion(LATEST_VERSION)
                                            .withName(aliasName)
                                            .withChecksum(checksum);

            PutBotAliasResult result = client.putBotAlias(request);

            System.out.println(String.format("The alias \"%s\" for the bot \"%s\" has been %s. Checksum: %s",
                                                aliasName, botName, checksum == null ? "created" : "updated",
                                                result.getChecksum()));
            return true;
        });
    }

    private static String getBotAliasChecksum(AmazonLexModelBuilding client, String botName, String aliasName) {
        if (!isBotAliasExist(client, botName, aliasName))
            return null;

        GetBotAliasesRequest request = new GetBotAliasesRequest().withBotName(botName);
        for(BotAliasMetadata botAliasMetadata: client.getBotAliases(request).getBotAliases()){
            if(botAliasMetadata.getBotName().equals(botName) && botAliasMetadata.getName().equals(aliasName))
                return client.getBotAlias(new GetBotAliasRequest().withBotName(botName).withName(aliasName)).getChecksum();
        }
        return null;
    }

    private static boolean isBotAliasExist(AmazonLexModelBuilding client, String botName, String aliasName) {
        for(BotAliasMetadata botAliasMetadata: client.getBotAliases(new GetBotAliasesRequest().withBotName(botName))
                                                        .getBotAliases()){
            if(botAliasMetadata.getBotName().equals(botName) && botAliasMetadata.getName().equals(aliasName))
                return true;
        }
        return false;
    }

    private static Boolean deleteBot(AmazonLexModelBuilding client, String botName) {
        if(!isBotExist(client, botName)){
            System.out.println(String.format("The bot \"%s\" does not exist.", botName));
            return false;
        }

        return performRequest(botName, () -> {
            client.deleteBot(new DeleteBotRequest().withName(botName));
            System.out.println(String.format("The bot \"%s\" has been deleted.", botName));
            return true;
        });

    }

    private static boolean isBotExist(AmazonLexModelBuilding client, String botName) {
        boolean exists = false;
        for(BotMetadata botMetadata: client.getBots(new GetBotsRequest()).getBots()){
            if(botMetadata.getName().equals(botName))
                return true;
        }
        return false;
    }

    private static Boolean performRequest(String entityName, Callable<Boolean> callableRequest) {
        try {
            return callableRequest.call();
        } catch (BadRequestException|ConflictException|InternalFailureException
                    |LimitExceededException|PreconditionFailedException|NotFoundException e) {
            System.out.println(String.format("Failure during creating \"%s\": %s", entityName, e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private static Boolean createBot(AmazonLexModelBuilding client, String botName, String description, String voiceId, List<String> intentNames, ProcessBehavior processBehavior, Prompt clarificationPrompt, Statement abortStatement) {
        return performRequest(botName, () -> {
            String checksum = getBotChecksum(client, botName);
            System.out.println(String.format("Bot checksum: %s", checksum));

            ArrayList<Intent> intents = new ArrayList<>();
            for(String intentName: intentNames)
                intents.add(new Intent().withIntentName(intentName).withIntentVersion(LATEST_VERSION));

            PutBotRequest request = new PutBotRequest()
                                        .withName(botName)
                                        .withChecksum(checksum)
                                        .withLocale(Locale.EnUS)
                                        .withVoiceId(voiceId)
                                        .withChildDirected(false)
                                        .withIdleSessionTTLInSeconds(60)
                                        .withAbortStatement(abortStatement)
                                        .withClarificationPrompt(clarificationPrompt)
                                        .withDescription(description)
                                        .withProcessBehavior(processBehavior)
                                        .withIntents(intents);

            PutBotResult result = client.putBot(request);

            System.out.println(String.format("The bot \"%s\" has been %s. Checksum: %s",
                                             botName, checksum == null ? "created" : "updated",
                                             result.getChecksum()));
            return true;
        });
    }

    private static String getBotChecksum(AmazonLexModelBuilding client, String botName) {
        if (isBotExist(client, botName))
            return client.getBot(new GetBotRequest().withName(botName).withVersionOrAlias(LATEST_VERSION)).getChecksum();

        return null;
    }

    private static void showSlotTypes(AmazonLexModelBuilding client) {
        System.out.println("--- Slot Types");
        GetSlotTypesResult slotTypesResult = client.getSlotTypes(new GetSlotTypesRequest());
        for(SlotTypeMetadata slotTypeMetadata: slotTypesResult.getSlotTypes())
            System.out.println(String.format("SlotType: %s; Description: %s", slotTypeMetadata.getName(), slotTypeMetadata.getDescription()));
    }

    private static void showIntents(AmazonLexModelBuilding client) {
        System.out.println("--- Intents");
        GetIntentsResult intentsResult = client.getIntents(new GetIntentsRequest());
        for(IntentMetadata intentMetadata: intentsResult.getIntents())
            System.out.println(String.format("Intent: %s: Description: %s", intentMetadata.getName(), intentMetadata.getDescription()));
    }

    private static void showBots(AmazonLexModelBuilding client) {
        System.out.println("--- Bots");
        GetBotsRequest getBotsRequest = new GetBotsRequest();
        GetBotsResult botsResult = client.getBots(getBotsRequest);
        for (BotMetadata botMetadata:botsResult.getBots()) {
            System.out.println(String.format("Name: %s; Status: %s", botMetadata.getName(), botMetadata.getStatus()));

            GetBotAliasesResult botAliasesResult = client.getBotAliases(new GetBotAliasesRequest().withBotName(botMetadata.getName()));
            for(BotAliasMetadata botAliasMetadata: botAliasesResult.getBotAliases())
                System.out.println(String.format("\tBot: %s; Alias: %s; Vesion: %s",
                        botAliasMetadata.getBotName(), botAliasMetadata.getName(), botAliasMetadata.getBotVersion()));

        }
    }
}
