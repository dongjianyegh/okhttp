import http.GET;
import http.Path;
import http.QueryMap;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

public class main {

    public static final String API_URL = "https://api.github.com";

    public static class Contributor {
        public final String login;
        public final int contributions;

        public Contributor(String login, int contributions) {
            this.login = login;
            this.contributions = contributions;
        }
    }

    private static class StringIntegerMap extends HashMap<String, Integer> {

    }

    public interface GitHub {
        @GET("/repos/{owner}/{repo}/contributors")

        Call<List<Contributor>> contributors(
                @Path("owner") String owner,
                @Path("repo") String repo,
                @QueryMap(encoded = false) StringIntegerMap queryMaps);
    }




    public static void main(String... args) throws IOException {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(API_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        // Create an instance of our GitHub API interface.
        GitHub github = retrofit.create(GitHub.class);

        StringIntegerMap map = new StringIntegerMap();

        map.put("testQueryName", 1);
        // Create a call instance for looking up Retrofit contributors.
        Call<List<Contributor>> call = github.contributors("square", "retrofit", map);

        // Fetch and print a list of the contributors to the library.
        Response<List<Contributor>> response = call.execute();
        System.out.println(response.raw().request().url());
        List<Contributor> contributors = response.body();
        for (Contributor contributor : contributors) {
            System.out.println(contributor.login + " (" + contributor.contributions + ")");
        }
    }
}
