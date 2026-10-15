import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class HealthServlet extends HttpServlet {
  // always return a fixed status[sc-ok]
  // allow LB to check this if still active
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException{
    res.setContentType("application/json");
    res.setStatus(HttpServletResponse.SC_OK);
    res.getWriter().write("{\"status\":\"Active\"}");
  }

}
