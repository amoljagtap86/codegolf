package workbench;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.sql.SQLException;
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

    /** Body is an optional {@link FailurePlan}; no body = happy path. A failed process is still 201. */
    @POST
    public Response create(FailurePlan plan, @Context UriInfo uriInfo) throws SQLException {
        long id = runner.start(plan);
        return Response.created(uriInfo.getAbsolutePathBuilder().path(Long.toString(id)).build())
                .entity(get(id))
                .build();
    }

    /** Retries a FAILED execution from its failed step. 409 if not FAILED (incl. a concurrent retry won). */
    @POST
    @Path("{id}/retry")
    public Execution retry(@PathParam("id") long id) throws SQLException {
        if (!runner.retry(id)) {
            Execution execution = get(id); // 404 if unknown
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "Execution " + id + " is " + execution.status()
                            + "; only FAILED executions can be retried"))
                    .build());
        }
        return get(id);
    }

    @GET
    @Path("{id}")
    public Execution get(@PathParam("id") long id) throws SQLException {
        return db.findExecution(id).orElseThrow(NotFoundException::new);
    }
}
