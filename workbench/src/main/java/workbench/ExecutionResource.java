package workbench;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@Path("/executions") // mounted under /api, see Main
@Produces(MediaType.APPLICATION_JSON)
public class ExecutionResource {

    private final Db db;
    private final Runner runner;

    public ExecutionResource(Db db, Runner runner) {
        this.db = db;
        this.runner = runner;
    }

    /** Body is an optional {@link Simulation}; no body = happy path. A failed process is still 201. */
    @POST
    public Response create(Simulation simulation, @Context UriInfo uriInfo) throws SQLException {
        long id = runner.start(simulation);
        return Response.created(uriInfo.getAbsolutePathBuilder().path(Long.toString(id)).build())
                .entity(get(id))
                .build();
    }

    /** Execution history, newest first. */
    @GET
    public List<ExecutionSummary> list(@QueryParam("limit") @DefaultValue("50") int limit) throws SQLException {
        if (limit < 1 || limit > 200) {
            throw error(Response.Status.BAD_REQUEST, "limit must be between 1 and 200");
        }
        return db.listExecutions(limit);
    }

    /**
     * Retries a FAILED execution from its failed step. 409 if not FAILED (incl. a concurrent retry won)
     * or if it was recorded under a different process definition.
     */
    @POST
    @Path("{id}/retry")
    public Execution retry(@PathParam("id") long id) throws SQLException {
        boolean retried;
        try {
            retried = runner.retry(id);
        } catch (Runner.IncompatibleExecutionException e) {
            throw error(Response.Status.CONFLICT, e.getMessage());
        }
        if (!retried) {
            Execution execution = get(id); // 404 if unknown
            throw error(Response.Status.CONFLICT,
                    "Execution " + id + " is " + execution.status() + "; only FAILED executions can be retried");
        }
        return get(id);
    }

    /** The process DAG: steps with their dependencies and stage, so the UI can lay out the timeline. */
    @GET
    @Path("steps")
    public List<ProcessDefinition.StepDef> steps() {
        return ProcessDefinition.IC_MONTHLY_RUN.steps();
    }

    /** Error response with a JSON body {"error": message}. */
    private static WebApplicationException error(Response.Status status, String message) {
        return new WebApplicationException(Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", message))
                .build());
    }

    @GET
    @Path("{id}")
    public Execution get(@PathParam("id") long id) throws SQLException {
        return db.findExecution(id).orElseThrow(NotFoundException::new);
    }
}
