package com.conveyal.datatools.manager.controllers.api;

import com.conveyal.datatools.manager.auth.Auth0UserProfile;
import com.conveyal.datatools.manager.models.JsonViews;
import com.conveyal.datatools.manager.models.Organization;
import com.conveyal.datatools.manager.models.Project;
import com.conveyal.datatools.manager.persistence.Persistence;
import com.conveyal.datatools.manager.utils.json.JsonManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.conveyal.datatools.common.utils.SparkUtils.haltWithMessage;
import static spark.Spark.delete;
import static spark.Spark.get;
import static spark.Spark.post;
import static spark.Spark.put;

/**
 * Created by landon on 1/30/17.
 */
public class OrganizationController {
    public static JsonManager<Organization> json = new JsonManager<>(Organization.class, JsonViews.UserInterface.class);
    public static final Logger LOG = LoggerFactory.getLogger(OrganizationController.class);

    public static Organization getOrganization (Request req, Response res) {
        String id = req.params("id");
        if (id == null) {
            haltWithMessage(req, 400, "Must specify valid organization id");
        }
        Organization org = Persistence.organizations.getById(id);
        return org;
    }

    public static Collection<Organization> getAllOrganizations (Request req, Response res) {
        Auth0UserProfile userProfile = req.attribute("user");
        boolean isOrgAdmin = userProfile.canAdministerOrganization();
        if (userProfile.canAdministerApplication()) {
            return Persistence.organizations.getAll();
        } else if (isOrgAdmin) {
            List<Organization> orgs = new ArrayList<>();
            orgs.add(userProfile.getOrganization());
            LOG.info("returning org {}", orgs);
            return orgs;
        } else {
            haltWithMessage(req, 401, "Must be application admin to view organizations");
        }
        return null;
    }

    // TODO Fix organization controllers to properly write beginDate/endDate to database as DATE_TIME type, and not string (or some other)
    public static Organization createOrganization (Request req, Response res) {
        Auth0UserProfile userProfile = req.attribute("user");
        if (userProfile.canAdministerApplication()) {
            Organization org = Persistence.organizations.create(req.body());
            return org;
        } else {
            haltWithMessage(req, 401, "Must be application admin to view organizations");
        }
        return null;
    }

    public static Organization updateOrganization (Request req, Response res) throws IOException {
        String organizationId = req.params("id");
        requestOrganizationById(req);
        Organization organization = Persistence.organizations.update(organizationId, req.body());

        return organization;
    }

    public static Organization deleteOrganization (Request req, Response res) {
        Organization org = requestOrganizationById(req);
        Collection<Project> organizationProjects = org.projects();
        if (organizationProjects != null && organizationProjects.size() > 0) {
            haltWithMessage(req, 400, "Cannot delete organization that is referenced by projects.");
        }
        Persistence.organizations.removeById(org.id);
        return org;
    }

    private static Organization requestOrganizationById(Request req) {
        Auth0UserProfile userProfile = req.attribute("user");
        String id = req.params("id");
        if (id == null) {
            haltWithMessage(req, 400, "Must specify valid organization id");
        }
        if (userProfile.canAdministerApplication()) {
            Organization org = Persistence.organizations.getById(id);
            if (org == null) {
                haltWithMessage(req, 400, "Organization does not exist");
            }
            return org;
        } else {
            haltWithMessage(req, 401, "Must be application admin to modify organization");
        }
        return null;
    }

    public static void register (String apiPrefix) {
        get(apiPrefix + "secure/organization/:id", OrganizationController::getOrganization, json::write);
        get(apiPrefix + "secure/organization", OrganizationController::getAllOrganizations, json::write);
        post(apiPrefix + "secure/organization", OrganizationController::createOrganization, json::write);
        put(apiPrefix + "secure/organization/:id", OrganizationController::updateOrganization, json::write);
        delete(apiPrefix + "secure/organization/:id", OrganizationController::deleteOrganization, json::write);
    }
}
