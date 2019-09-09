package org.hl7.davinci.priorauth;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimerTask;
import java.util.UUID;
import java.util.logging.Logger;

import javax.enterprise.context.RequestScoped;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.hl7.davinci.priorauth.Endpoint.RequestType;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Claim.RelatedClaimComponent;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.ClaimResponse.ClaimResponseStatus;
import org.hl7.fhir.r4.model.ClaimResponse.RemittanceOutcome;
import org.hl7.fhir.r4.model.ClaimResponse.Use;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Subscription.SubscriptionChannelType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.Subscription;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Claim.ClaimStatus;
import org.hl7.fhir.r4.model.Claim.ItemComponent;

import ca.uhn.fhir.parser.IParser;

/**
 * The Claim endpoint to READ, SEARCH for, and DELETE submitted claims.
 */
@RequestScoped
@Path("Claim")
public class ClaimEndpoint {

  static final Logger logger = PALogger.getLogger();

  String REQUIRES_BUNDLE = "Prior Authorization Claim/$submit Operation requires a Bundle with a single Claim as the first entry and supporting resources.";
  String PROCESS_FAILED = "Unable to process the request properly. Check the log for more details.";

  public static String baseUri = null;

  @Context
  private UriInfo uri;

  @GET
  @Path("/")
  @Produces({ MediaType.APPLICATION_JSON, "application/fhir+json" })
  public Response readClaimJson(@QueryParam("identifier") String id, @QueryParam("patient.identifier") String patient,
      @QueryParam("status") String status) {
    Map<String, Object> constraintMap = new HashMap<String, Object>();
    constraintMap.put("id", id);
    constraintMap.put("patient", patient);
    if (status != null)
      constraintMap.put("status", status);
    return Endpoint.read(Database.CLAIM, constraintMap, uri, RequestType.JSON);
  }

  @GET
  @Path("/")
  @Produces({ MediaType.APPLICATION_XML, "application/fhir+xml" })
  public Response readClaimXml(@QueryParam("identifier") String id, @QueryParam("patient.identifier") String patient,
      @QueryParam("status") String status) {
    Map<String, Object> constraintMap = new HashMap<String, Object>();
    constraintMap.put("id", id);
    constraintMap.put("patient", patient);
    if (status != null)
      constraintMap.put("status", status);
    return Endpoint.read(Database.CLAIM, constraintMap, uri, RequestType.XML);
  }

  @DELETE
  @Path("/")
  @Produces({ MediaType.APPLICATION_JSON, "application/fhir+json" })
  public Response deleteClaimJson(@QueryParam("identifier") String id,
      @QueryParam("patient.identifier") String patient) {
    return Endpoint.delete(id, patient, Database.CLAIM, RequestType.JSON);
  }

  @DELETE
  @Path("/")
  @Produces({ MediaType.APPLICATION_JSON, "application/fhir+xml" })
  public Response deleteClaimXml(@QueryParam("identifier") String id,
      @QueryParam("patient.identifier") String patient) {
    return Endpoint.delete(id, patient, Database.CLAIM, RequestType.XML);
  }

  @POST
  @Path("/$submit")
  @Consumes({ MediaType.APPLICATION_JSON, "application/fhir+json" })
  public Response submitOperationJson(String body) {
    return submitOperation(body, RequestType.JSON);
  }

  @POST
  @Path("/$submit")
  @Consumes({ MediaType.APPLICATION_XML, "application/fhir+xml" })
  public Response submitOperationXml(String body) {
    return submitOperation(body, RequestType.XML);
  }

  /**
   * The submitOperation function for both json and xml
   * 
   * @param body        - the body of the post request.
   * @param requestType - the RequestType of the request.
   * @return - claimResponse response
   */
  private Response submitOperation(String body, RequestType requestType) {
    logger.info("POST /Claim/$submit fhir+" + requestType.name());
    if (baseUri == null) {
      baseUri = uri.getBaseUri().toString();
    }

    String id = null;
    String patient = null;
    Status status = Status.OK;
    String formattedData = null;
    try {
      IParser parser = requestType == RequestType.JSON ? App.FHIR_CTX.newJsonParser() : App.FHIR_CTX.newXmlParser();
      IBaseResource resource = parser.parseResource(body);
      if (resource instanceof Bundle) {
        Bundle bundle = (Bundle) resource;
        if (bundle.hasEntry() && (bundle.getEntry().size() > 1) && bundle.getEntryFirstRep().hasResource()
            && bundle.getEntryFirstRep().getResource().getResourceType() == ResourceType.Claim) {
          Bundle responseBundle = processBundle(bundle);
          if (responseBundle == null) {
            // Failed processing bundle...
            status = Status.BAD_REQUEST;
            OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.ERROR, IssueType.INVALID, PROCESS_FAILED);
            formattedData = requestType == RequestType.JSON ? App.getDB().json(error) : App.getDB().xml(error);
          } else {
            ClaimResponse response = FhirUtils.getClaimResponseFromBundle(responseBundle);
            if (response.getIdElement().hasIdPart()) {
              id = response.getIdElement().getIdPart();
            }
            if (response.hasPatient()) {
              patient = FhirUtils.getPatientIdFromResource(response);
            }
            formattedData = requestType == RequestType.JSON ? App.getDB().json(responseBundle)
                : App.getDB().xml(responseBundle);
          }
        } else {
          // Claim is required...
          status = Status.BAD_REQUEST;
          OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.ERROR, IssueType.INVALID, REQUIRES_BUNDLE);
          formattedData = requestType == RequestType.JSON ? App.getDB().json(error) : App.getDB().xml(error);
        }
      } else {
        // Bundle is required...
        status = Status.BAD_REQUEST;
        OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.ERROR, IssueType.INVALID, REQUIRES_BUNDLE);
        formattedData = requestType == RequestType.JSON ? App.getDB().json(error) : App.getDB().xml(error);
      }
    } catch (Exception e) {
      // The submission failed so spectacularly that we need
      // catch an exception and send back an error message...
      status = Status.BAD_REQUEST;
      OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.FATAL, IssueType.STRUCTURE, e.getMessage());
      formattedData = requestType == RequestType.JSON ? App.getDB().json(error) : App.getDB().xml(error);
    }
    ResponseBuilder builder = requestType == RequestType.JSON
        ? Response.status(status).type("application/fhir+json").entity(formattedData)
        : Response.status(status).type("application/fhir+xml").entity(formattedData);
    if (id != null) {
      builder = builder.header("Location",
          uri.getBaseUri() + "ClaimResponse?identifier=" + id + "&patient.identifier=" + patient);
    }
    return builder.build();
  }

  /**
   * Process the $submit operation Bundle. Theoretically, this is where business
   * logic should be implemented or overridden.
   * 
   * @param bundle Bundle with a Claim followed by other required resources.
   * @return ClaimResponse with the result.
   */
  private Bundle processBundle(Bundle bundle) {
    logger.fine("ClaimEndpoint::processBundle");
    // Store the submission...
    // Generate a shared id...
    String id = UUID.randomUUID().toString();

    // get the patient
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();
    String patient = FhirUtils.getPatientIdFromResource(claim);

    String claimId = id;
    String responseDisposition = "Unknown";
    ClaimResponseStatus responseStatus = ClaimResponseStatus.ACTIVE;
    ClaimStatus status = claim.getStatus();
    boolean delayedUpdate = false;
    String delayedDisposition = "";

    if (status == ClaimStatus.CANCELLED) {
      // Cancel the claim...
      claimId = claim.getIdElement().getIdPart();
      if (cancelClaim(claimId, patient)) {
        responseStatus = ClaimResponseStatus.CANCELLED;
        responseDisposition = "Cancelled";
      } else
        return null;
    } else {
      // Store the claim...
      claim.setId(id);
      String relatedId = null;
      String claimStatusStr = FhirUtils.getStatusFromResource(claim);
      Map<String, Object> claimMap = new HashMap<String, Object>();
      claimMap.put("id", id);
      claimMap.put("patient", patient);
      claimMap.put("status", claimStatusStr);
      claimMap.put("resource", claim);
      RelatedClaimComponent related = getRelatedComponent(claim);
      if (related != null) {
        // This is an update...
        relatedId = related.getIdElement().asStringValue();
        relatedId = App.getDB().getMostRecentId(relatedId);
        logger.info("ClaimEndpoint::Udpated id to most recent: " + relatedId);
        claimMap.put("related", relatedId);

        // Check if related is cancelled in the DB
        Map<String, Object> constraintMap = new HashMap<String, Object>();
        constraintMap.put("id", relatedId);
        String relatedStatusStr = App.getDB().readStatus(Database.CLAIM, constraintMap);
        ClaimStatus relatedStatus = ClaimStatus.fromCode(relatedStatusStr);
        if (relatedStatus == Claim.ClaimStatus.CANCELLED) {
          logger.warning(
              "ClaimEndpoint::Unable to submit update to claim " + relatedId + " because it has been cancelled");
          return null;
        }
      }

      if (!App.getDB().write(Database.CLAIM, claimMap))
        return null;

      // Store the bundle...
      bundle.setId(id);
      Map<String, Object> bundleMap = new HashMap<String, Object>();
      bundleMap.put("id", id);
      bundleMap.put("patient", patient);
      bundleMap.put("resource", bundle);
      App.getDB().write(Database.BUNDLE, bundleMap);

      // Store the claim items...
      if (claim.hasItem()) {
        processClaimItems(claim, id, relatedId);
      }

      // generate random responses since not cancelling
      switch (getRand(3)) {
      case 1:
        responseDisposition = "Granted";
        break;
      case 2:
        responseDisposition = "Pending";

        switch (getRand(2)) {
        case 1:
          delayedDisposition = "Granted";
          break;
        case 2:
          delayedDisposition = "Denied";
          break;
        }

        delayedUpdate = true;
        break;
      case 3:
      default:
        responseDisposition = "Denied";
        break;
      }
    }
    // Process the claim...
    // TODO

    // Generate the claim response...
    Bundle responseBundle = generateAndStoreClaimResponse(bundle, claim, id, responseDisposition, responseStatus,
        patient);

    if (delayedUpdate) {
      // schedule the update
      scheduleClaimUpdate(bundle, id, patient, delayedDisposition);
    }

    // Respond...
    return responseBundle;
  }

  /**
   * Process the claim items in the database. For a new claim add the items, for
   * an updated claim update the items.
   * 
   * @param claim     - the claim the items belong to.
   * @param id        - the id of the claim.
   * @param relatedId - the related id to this claim.
   * @return true if all updates successful, false otherwise.
   */
  private boolean processClaimItems(Claim claim, String id, String relatedId) {
    boolean ret = true;
    String claimStatusStr = FhirUtils.getStatusFromResource(claim);
    if (relatedId != null) {
      // Update the items...
      for (ItemComponent item : claim.getItem()) {
        boolean itemIsCancelled = false;
        String extUrl = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-itemCancelled";
        if (item.hasModifierExtension()) {
          List<Extension> exts = item.getModifierExtension();
          for (Extension ext : exts) {
            if (ext.getUrl().equals(extUrl) && ext.hasValue()) {
              Type type = ext.getValue();
              itemIsCancelled = type.castToBoolean(type).booleanValue();
            }
          }
        }

        Map<String, Object> dataMap = new HashMap<String, Object>();
        Map<String, Object> constraintMap = new HashMap<String, Object>();
        constraintMap.put("id", relatedId);
        constraintMap.put("sequence", item.getSequence());
        dataMap.put("id", id);
        dataMap.put("sequence", item.getSequence());
        dataMap.put("status", itemIsCancelled ? ClaimStatus.CANCELLED.getDisplay().toLowerCase() : claimStatusStr);

        // Update if item exists otherwise add to database
        if (App.getDB().readStatus(Database.CLAIM_ITEM, constraintMap) == null) {
          App.getDB().write(Database.CLAIM_ITEM, dataMap);
        } else {
          if (!App.getDB().update(Database.CLAIM_ITEM, constraintMap, dataMap))
            ret = false;
        }
      }
    } else {
      // Add the claim items...
      for (ItemComponent item : claim.getItem()) {
        Map<String, Object> itemMap = new HashMap<String, Object>();
        itemMap.put("id", id);
        itemMap.put("sequence", item.getSequence());
        itemMap.put("status", claimStatusStr);
        if (!App.getDB().write(Database.CLAIM_ITEM, itemMap))
          ret = false;
      }
    }
    return ret;
  }

  /**
   * Determine if a cancel can be performed and then update the DB to reflect the
   * cancel
   * 
   * @param claimId - the claim id to cancel.
   * @param patient - the patient for the claim to cancel.
   * @return true if the claim was cancelled successfully and false otherwise
   */
  private boolean cancelClaim(String claimId, String patient) {
    boolean result;
    Map<String, Object> claimConstraintMap = new HashMap<String, Object>();
    claimConstraintMap.put("id", claimId);
    claimConstraintMap.put("patient", patient);
    Claim initialClaim = (Claim) App.getDB().read(Database.CLAIM, claimConstraintMap);
    if (initialClaim != null) {
      if (initialClaim.getStatus() != ClaimStatus.CANCELLED) {
        // Cancel the claim...
        initialClaim.setStatus(ClaimStatus.CANCELLED);
        Map<String, Object> dataMap = new HashMap<String, Object>();
        Map<String, Object> constraintMap = new HashMap<String, Object>();
        constraintMap.put("id", claimId);
        dataMap.put("status", ClaimStatus.CANCELLED.getDisplay().toLowerCase());
        dataMap.put("resource", initialClaim);
        App.getDB().update(Database.CLAIM, constraintMap, dataMap);

        cascadeCancel(claimId);

        // Cancel items...
        dataMap = new HashMap<String, Object>();
        constraintMap = new HashMap<String, Object>();
        constraintMap.put("id", App.getDB().getMostRecentId(claimId));
        dataMap.put("status", ClaimStatus.CANCELLED.getDisplay().toLowerCase());
        App.getDB().update(Database.CLAIM_ITEM, constraintMap, dataMap);

        result = true;
      } else {
        logger.warning("ClaimEndpoint::Claim " + claimId + " is already cancelled");
        result = false;
      }
    } else {
      logger.warning("ClaimEndpoint::Claim " + claimId + " does not exist. Unable to cancel");
      result = false;
    }
    return result;
  }

  /**
   * Helper function to complete the cascade aspect of a Claim cancel. When a
   * Claim is cancelled this will cascade the cancel to all related Claims (both
   * up and downstream). A cancel updates the database status as well as update
   * the resource status
   * 
   * @param claimId - the id of the original Claim to be cancelled
   */
  private void cascadeCancel(String claimId) {
    // Cascade delete shared maps
    Map<String, Object> dataMap = new HashMap<String, Object>();
    dataMap.put("status", ClaimStatus.CANCELLED.getDisplay().toLowerCase());
    dataMap.put("resource", null);
    Map<String, Object> constraintMap = new HashMap<String, Object>();
    constraintMap.put("id", null);

    // Cascade up to all the Claims submitted after this which reference this Claim
    Map<String, Object> readConstraintMap = new HashMap<String, Object>();
    readConstraintMap.put("related", claimId);
    Claim referencingClaim = (Claim) App.getDB().read(Database.CLAIM, readConstraintMap);
    String referecingId;

    while (referencingClaim != null) {
      // Update referencing claim to cancelled
      referencingClaim.setStatus(ClaimStatus.CANCELLED);
      referecingId = referencingClaim.getIdElement().getIdPart();
      constraintMap.replace("id", referecingId);
      dataMap.replace("resource", referencingClaim);
      App.getDB().update(Database.CLAIM, constraintMap, dataMap);

      // Get the new referencing claim
      readConstraintMap.replace("related", referecingId);
      referencingClaim = (Claim) App.getDB().read(Database.CLAIM, readConstraintMap);
    }

    // Cascade the cancel to all related Claims...
    // Follow each related until it is NULL
    constraintMap.replace("id", claimId);
    String relatedId = App.getDB().readRelated(Database.CLAIM, constraintMap);
    Claim relatedClaim;

    while (relatedId != null) {
      // Update related claim to cancelled
      constraintMap.replace("id", relatedId);
      relatedClaim = (Claim) App.getDB().read(Database.CLAIM, constraintMap);
      relatedClaim.setStatus(ClaimStatus.CANCELLED);
      dataMap.replace("resource", relatedClaim);
      App.getDB().update(Database.CLAIM, constraintMap, dataMap);

      // Get the new related id from the db
      relatedId = App.getDB().readRelated(Database.CLAIM, constraintMap);
    }
  }

  /**
   * Update the claim and generate a new ClaimResponse.
   * 
   * @param bundle      - the Bundle the Claim is a part of.
   * @param claimId     - the Claim ID.
   * @param patient     - the Patient ID.
   * @param disposition - the new disposition of the updated Claim.
   */
  private Bundle updateClaim(Bundle bundle, String claimId, String patient, String disposition) {
    logger.info("ClaimEndpoint::updateClaim(" + claimId + "/" + patient + ", disposition: " + disposition + ")");

    // Generate a new id...
    String id = UUID.randomUUID().toString();

    // Get the claim from the database
    Map<String, Object> claimConstraintMap = new HashMap<String, Object>();
    claimConstraintMap.put("id", claimId);
    claimConstraintMap.put("patient", patient);
    Claim claim = (Claim) App.getDB().read(Database.CLAIM, claimConstraintMap);
    if (claim != null)
      return generateAndStoreClaimResponse(bundle, claim, id, "Granted", ClaimResponseStatus.ACTIVE, patient);
    else
      return null;
  }

  /**
   * Get the related claim for an update to a claim (replaces relationship)
   * 
   * @param claim - the base Claim resource.
   * @return the first related claim with relationship "replaces" or null if no
   *         matching related resource.
   */
  private RelatedClaimComponent getRelatedComponent(Claim claim) {
    if (claim.hasRelated()) {
      for (RelatedClaimComponent relatedComponent : claim.getRelated()) {
        if (relatedComponent.hasRelationship()) {
          for (Coding code : relatedComponent.getRelationship().getCoding()) {
            if (code.getCode().equals("replaces")) {
              // This claim is an update to an old claim
              return relatedComponent;
            }
          }
        }
      }
    }
    return null;
  }

  /**
   * Generate a new ClaimResponse and store it in the database.
   *
   * @param bundle              The original bundle submitted to the server
   *                            requesting priorauthorization.
   * @param claim               The claim which this ClaimResponse is in reference
   *                            to.
   * @param id                  The new identifier for this ClaimResponse.
   * @param responseDisposition The new disposition for this ClaimResponse
   *                            (Granted, Pending, Cancelled, Declined ...).
   * @param responseStatus      The new status for this ClaimResponse (Active,
   *                            Cancelled, ...).
   * @param patient             The identifier for the patient this ClaimResponse
   *                            is referring to.
   * @return ClaimResponse that has been generated and stored in the Database.
   */
  private Bundle generateAndStoreClaimResponse(Bundle bundle, Claim claim, String id, String responseDisposition,
      ClaimResponseStatus responseStatus, String patient) {
    logger.info("ClaimEndpoint::generateAndStoreClaimResponse(" + id + "/" + patient + ", disposition: "
        + responseDisposition + ", status: " + responseStatus + ")");

    // Generate the claim response...
    ClaimResponse response = new ClaimResponse();
    String claimId = App.getDB().getMostRecentId(claim.getIdElement().getIdPart());
    response.setStatus(responseStatus);
    response.setType(claim.getType());
    response.setUse(Use.PREAUTHORIZATION);
    response.setPatient(claim.getPatient());
    response.setCreated(new Date());
    if (claim.hasInsurer()) {
      response.setInsurer(claim.getInsurer());
    } else {
      response.setInsurer(new Reference().setDisplay("Unknown"));
    }
    response.setRequest(new Reference(
        baseUri + "Claim?identifier=" + claim.getIdElement().getIdPart() + "&patient.identifier=" + patient));
    if (responseDisposition == "Pending") {
      response.setOutcome(RemittanceOutcome.QUEUED);
    } else {
      response.setOutcome(RemittanceOutcome.COMPLETE);
    }
    response.setDisposition(responseDisposition);
    response.setPreAuthRef(id);
    // TODO response.setPreAuthPeriod(period)?
    response.setId(id);

    // Create the responseBundle
    Bundle responseBundle = new Bundle();
    responseBundle.setId(id);
    responseBundle.setType(Bundle.BundleType.COLLECTION);
    BundleEntryComponent responseEntry = responseBundle.addEntry();
    responseEntry.setResource(response);
    responseEntry.setFullUrl(id);
    for (BundleEntryComponent entry : bundle.getEntry()) {
      responseBundle.addEntry(entry);
    }

    // Store the claim respnose...
    Map<String, Object> responseMap = new HashMap<String, Object>();
    responseMap.put("id", id);
    responseMap.put("claimId", claimId);
    responseMap.put("patient", patient);
    responseMap.put("status", FhirUtils.getStatusFromResource(response));
    responseMap.put("resource", responseBundle);
    App.getDB().write(Database.CLAIM_RESPONSE, responseMap);

    return responseBundle;
  }

  /**
   * A TimerTask for updating claims.
   */
  class UpdateClaimTask extends TimerTask {
    public Bundle bundle;
    public String claimId;
    public String patient;
    public String disposition;

    UpdateClaimTask(Bundle bundle, String claimId, String patient, String disposition) {
      this.bundle = bundle;
      this.claimId = claimId;
      this.patient = patient;
      this.disposition = disposition;
    }

    @Override
    public void run() {
      if (updateClaim(bundle, claimId, patient, disposition) != null) {
        // Check for subscription
        Map<String, Object> constraintMap = new HashMap<String, Object>();
        constraintMap.put("claimResponseId", claimId);
        constraintMap.put("patient", patient);
        Subscription subscription = (Subscription) App.getDB().read(Database.SUBSCRIPTION, constraintMap);

        // Send notification to the subscriber
        if (subscription != null) {
          SubscriptionChannelType subscriptionType = subscription.getChannel().getType();
          String endpoint = subscription.getChannel().getEndpoint();
          if (subscriptionType == SubscriptionChannelType.RESTHOOK
              || subscriptionType == SubscriptionChannelType.WEBSOCKET) {
            SubscriptionHandler.sendNotifcation(subscriptionType, endpoint);
          } else {
            logger.severe(
                "Invalid subscription channel type " + subscriptionType.name() + ". Must be Resthook or websocket");
          }
        }
      }
    }
  }

  /**
   * Schedule an update to the Claim to support pending actions.
   *
   * @param bundle      - the bundle containing the claim.
   * @param id          - the Claim ID.
   * @param patient     - the Patient ID.
   * @param disposition - the new disposition of the updated Claim.
   */
  private void scheduleClaimUpdate(Bundle bundle, String id, String patient, String disposition) {
    App.timer.schedule(new UpdateClaimTask(bundle, id, patient, disposition), 30000); // 30s
  }

  /**
   * Get a random number between 1 and max.
   *
   * @param max The largest the random number could be.
   * @return int representing the random number.
   */
  private int getRand(int max) {
    Date date = new Date();
    return (int) ((date.getTime() % max) + 1);
  }

}
