package workbench;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.sql.SQLException;

@Path("/api/executions")
@Produces(MediaType.APPLICATION_JSON)
public class ExecutionResource {

    private final Db db;
    private final Runner runner;

    public ExecutionResource(Db db, Runner runner) {
        this.db = db;
        this.runner = runner;
    }

    @POST
    public Response create(@Context UriInfo uriInfo) throws SQLException {
        long id = runner.start();
        return Response.created(uriInfo.getAbsolutePathBuilder().path(Long.toString(id)).build())
                .entity(get(id))
                .build();
    }

    @GET
    @Path("{id}")
    public Execution get(@PathParam("id") long id) throws SQLException {
        return db.findExecution(id).orElseThrow(NotFoundException::new);
    }
}
