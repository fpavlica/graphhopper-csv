package com.graphhopper.example;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.LMProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.weighting.custom.CustomProfile;
import com.graphhopper.routing.Router;
import com.graphhopper.storage.*;
import com.graphhopper.util.*;
import com.graphhopper.util.exceptions.ConnectionNotFoundException;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.gtfs.GraphHopperGtfs;
import com.graphhopper.gtfs.PtRouter;
import com.graphhopper.gtfs.PtRouterImpl;
import com.graphhopper.gtfs.Request;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.lang.Exception;

import org.apache.commons.io.file.Counters.Counter;

import java.time.Instant;

import static com.graphhopper.json.Statement.If;
import static com.graphhopper.json.Statement.Op.LIMIT;
import static com.graphhopper.json.Statement.Op.MULTIPLY;

public class PostcodeMatrix {
    public static void main(String[] args) throws IOException {
        boolean onlyGraphs = false;
        if (args.length == 2 || args.length == 4) {
            // String defaultFpath = "great-britain-latest.osm.pbf"
            // final String defaultOsmPath = "core/files/andorra.osm.pbf";
            // String osmFpath = args.length >= 1 ? args[0] : defaultOsmPath;
            String configFpath = args[0];
            String chosenProfile = args.length == 4 ? args[2] : "car";
            String outFpath = args.length == 4 ? args[3] : "routing_output.csv";
            GraphHopperConfig config = readPostcodeConfig(configFpath);
            GraphHopper hopper = createGraphHopperInstance(config);
            System.out.println("Instance created, starting routing");
            // GraphHopper hopper = createGraphHopperInstance(osmFpath);
            // routing(hopper);
            // routingListTemp(hopper);

            try {
                if (args[1].equals("onlygraphs")){
                    //skip routing
                    System.out.println("Graphs built, ending.");
                } else {
                    String locationsFpath = args[1];
                    routingFromFile(hopper, locationsFpath, outFpath, chosenProfile, config);
                }
                // speedModeVersusFlexibleMode(hopper);
                // headingAndAlternativeRoute(hopper);
                // customizableRouting(relDir + "core/files/andorra.osm.pbf");
                
                // release resources to properly shutdown or start a new instance
            } finally {
            hopper.close();
            }
        } else {
            System.out.println("Usage: jarfile.jar config_file locations_path [profile output_path]");
        }
    }
    // public static void main(String[] args) throws IOException {
    //     if (args.length == 2 || args.length == 3) {
    //         // String defaultFpath = "great-britain-latest.osm.pbf"
    //         // final String defaultOsmPath = "core/files/andorra.osm.pbf";
    //         // String osmFpath = args.length >= 1 ? args[0] : defaultOsmPath;
    //         String osmFpath = args[0];
    //         String locationsFpath = args[1];
    //         String outFpath = args.length == 3 ? args[2] : "routing_output.csv";
    //         GraphHopperGtfs hopperGtfs = createGraphHopperInstance(osmFpath);
    //         // GraphHopper hopper = createGraphHopperInstance(osmFpath);
    //         // routing(hopper);
    //         // routingListTemp(hopper);
    //         routingFromFile(hopper, locationsFpath, outFpath, "car");
    //         // speedModeVersusFlexibleMode(hopper);
    //         // headingAndAlternativeRoute(hopper);
    //         // customizableRouting(relDir + "core/files/andorra.osm.pbf");
            
    //         // release resources to properly shutdown or start a new instance
    //         hopper.close();
    //     } else {
    //         System.out.println("Usage: jarfile.jar osm_path locations_path [output_path]");
    //     }
    // }

    private static GraphHopperConfig readPostcodeConfig(String path) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = null;
            GraphHopperConfig config = new GraphHopperConfig();
            List<Profile> profiles = new ArrayList<Profile>();
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    // empty line, skip
                    continue;
                }

                String[] split = line.split(":", 2);
                if (split.length != 2) {
                    // no ':' found.
                    throw new RuntimeException("Could not parse config file. Format should be: "
                        + "\"key: value \\n #comment \\n profile:name=n, vehicle=v, weighting=w\"");
                }

                String key = split[0].trim();
                System.out.println("Config key: \""+key+"\", value: \"" + split[1].trim() +"\"");
                if (key.charAt(0) == '#') {
                    continue;
                }
                if (key.equals("profile")) {
                    // profiles are handled different than other keys
                    String[] profileInfoArr = split[1].split(",");
                    Profile newProfile = new Profile("default_profile_name");
                    for (String profileKvp : profileInfoArr) {

                        String[] pkvpArr = profileKvp.split("=");
                        String pk = pkvpArr[0].trim();
                        String pv = pkvpArr[1].trim();
                        if (pk.equals("name")) {
                            newProfile.setName(pv);
                        } else if (pk.equals("vehicle")) {
                            newProfile.setVehicle(pv);
                        } else if (pk.equals("weighting")) {
                            newProfile.setWeighting(pv);
                        } else {
                            throw new RuntimeException("Could not parse profile. Unknown key: \"" + pk + "\"");
                        }
                    }
                    profiles.add(newProfile);
                } else {
                    String value = split[1].trim();
                    //add key value to config object
                    config.putObject(key, value);
                }
            }
            //set all the profiles after reading the whole file
            config.setProfiles(profiles);
            return config;
        }
    }

    static GraphHopper createGraphHopperInstance(GraphHopperConfig config) {
        GraphHopper hopper;
        if (config.has("gtfs.file")) {
            hopper = new GraphHopperGtfs(config);
        } else {
            hopper = new GraphHopper();
        }

        // GraphHopper hopper = new GraphHopper();
        // hopper.setOSMFile(ghLoc);
        // specify where to store graphhopper files
        // hopper.setGraphHopperLocation("graph-cache");
        // hopper.setGraphHopperLocation("target/routing-graph-cache");

        // see docs/core/profiles.md to learn more about profiles
        // hopper.setProfiles(new Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(false));

        // this enables speed mode for the profile we called car
        //hopper.getCHPreparationHandler().setCHProfiles(new CHProfile("car"));

        // System.out.println("printing config before init:");
        System.out.println(config.toString());
        hopper.init(config);

        // set CHprofiles, apparently this should make things faster
        List<CHProfile> chProfiles = new ArrayList<CHProfile>(config.getProfiles().size());
        for (int i= 0; i <config.getProfiles().size(); ++i) {
            chProfiles.add(new CHProfile(config.getProfiles().get(i).getName()));
        }
        System.out.println(chProfiles.toString());
        hopper.getCHPreparationHandler().setCHProfiles(chProfiles);
        // System.out.println("hopper init finished 167");
        // now this can take minutes if it imports or a few seconds for loading of course this is dependent on the area you import
        hopper.importOrLoad();
        // System.out.println("hopper import or load finished 170");
        return hopper;
    }
    static GraphHopper createGraphHopperInstance(String ghLoc) {
        GraphHopper hopper = new GraphHopper();
        hopper.setOSMFile(ghLoc);
        // specify where to store graphhopper files
        // hopper.setGraphHopperLocation("graph-cache");
        hopper.setGraphHopperLocation("target/routing-graph-cache");

        // see docs/core/profiles.md to learn more about profiles
        hopper.setProfiles(new Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(false));

        // this enables speed mode for the profile we called car
        hopper.getCHPreparationHandler().setCHProfiles(new CHProfile("car"));

        // now this can take minutes if it imports or a few seconds for loading of course this is dependent on the area you import
        hopper.importOrLoad();
        return hopper;
    }

    // public static void routingFromFile(GraphHopper hopper, String infpath, String outfpath) throws IOException {
    //     routingFromFile(hopper, infpath, outfpath, "car");
    // }

    public static void routingFromFile(GraphHopper hopper, String infpath, String outfpath, String profile, GraphHopperConfig ghc) throws IOException {

        BufferedReader br = new BufferedReader(new FileReader(infpath));
        PrintWriter pw = new PrintWriter(new FileWriter(outfpath, false));
        try{
            String headers = br.readLine(); // skip first line
            // pw.println("PO1,PD1,time_" + profile + ",time_" + profile + "_added_up");
            pw.println("origin,destination,time_" + profile + ",time_" + profile + "_added_up");
            
            String line=null;
            
            Object router = null;
            System.out.println("about to create ptrouter");
            if (hopper instanceof GraphHopperGtfs) {
                GraphHopperGtfs hopperGtfs = (GraphHopperGtfs) hopper;
                PtRouter ptRouter = myCreateRouter(hopperGtfs, ghc);
                router = ptRouter;
            }
            else {
                // throw new UnsupportedOperationException("only public transport supported for now");
                // later see if I can make GraphHopper.createRouter() public
                // router = hopper.createRouter();
                router = hopper;
            }
            
            int counter = 0;
            long time0 = System.nanoTime();
            System.out.println("about to read lines of infile");
            while ((line = br.readLine()) != null){ 
                if (counter % 100000 == 0) {
                    long time1 = System.nanoTime();
                    long timdediff = (time1 - time0) / 10000;
                    System.out.println("line read counter "+ counter + ", time " + timdediff + "s");
                    time0 = System.nanoTime();
                } 
                counter += 1;


                
                String[] values = line.split(",");
                // String originPostcode = values[1]; //0th value is just index with our files
                // String destPostcode = values[2];
                // Float originLat = Float.parseFloat(values[3]);
                // Float originLng = Float.parseFloat(values[4]);
                // Float destLat = Float.parseFloat(values[5]);
                // Float destLng = Float.parseFloat(values[6]);
                String originPostcode = values[0]; //0th value is just index with our files
                String destPostcode = values[1];
                try {
                    Float originLat = Float.parseFloat(values[2]);
                    Float originLng = Float.parseFloat(values[3]);
                    Float destLat = Float.parseFloat(values[4]);
                    Float destLng = Float.parseFloat(values[5]);
                    
                    GHRequest req = new GHRequest(originLat, originLng, destLat, destLng);
                    // System.out.println("request made");
                    String toWrite = "";
                    try {
                        // long[] result = routing(router, req, profile, ghc);
                        long result = routing(router, req, profile, ghc);
                        // long[] result = routing(hopper, req, profile, ghc);
                        // System.out.println("routed");
                        // System.out.println("Routed, time : " + result[0] + ", inst time: " + result[1]);
                        // toWrite = originPostcode + "," + destPostcode + "," + result[0] + "," + result[1];
                        toWrite = line + "," + result;
                    } catch (Exception e) {
                        // toWrite = originPostcode + "," + destPostcode +",-4,-4,\"" + e.getMessage()+ "\"";
                        toWrite = line +",-4,\"" + e.getMessage()+ "\"";
                    }
                    pw.println(toWrite);
                } catch (Exception e) {
                    System.out.println(e);
                    try {
                        pw.write(line + "," + e.toString());
                    } catch (Exception e2) {
                        System.out.println("could not printwrite: " + e2.toString());
                    }
                    continue;
                }
                // System.out.println("saved to file");
            }
            // System.out.println("lines all read");
        } finally {
            br.close();
            pw.close();
        }
    }
    
    // public static void routingListTemp(GraphHopper hopper) {
    //     List<GHRequest> templist = new ArrayList<GHRequest>();
    //     templist.add(new GHRequest(42.508552, 1.532936, 42.507508, 1.528773));
    //     routing(hopper, templist, "car");
    // }

    // class MyRoutingResult {
    //     private long timeInMs, instructionTime;
    //     public MyRoutingResult(long timeInMs, long instructionTime) {
    //         this.timeInMs = timeInMs;
    //         this.instructionTime = instructionTime;
    //     }
    //     public long getTimeInMs() {return timeInMs;}
    //     public long getInstructionTime() {return instructionTime;}
    // }

    ////////

    private static GHResponse myRoute(PtRouter router,GHRequest ghRequest, GraphHopperConfig ghc) {
        // return myRoute(request);
        List<GHPoint> points = ghRequest.getPoints();
        Request req = new Request(points.get(0).lat, points.get(0).lon, points.get(1).lat, points.get(1).lon);
        req.setEarliestDepartureTime(Instant.parse("2022-07-01T09:00:00.00Z"));
        // System.out.println("req created, about to create router and route");
        return router.route(req);
    }
    private static GHResponse myRoute(GraphHopper router,GHRequest ghRequest, GraphHopperConfig ghc) {
        // return myRoute(request);
        // List<GHPoint> points = ghRequest.getPoints();
        // Request req = new Request(points.get(0).lat, points.get(0).lon, points.get(1).lat, points.get(1).lon);
        // req.setEarliestDepartureTime(Instant.parse("2022-07-01T09:00:00.00Z"));
        // System.out.println("req created, about to create router and route");
        return router.route(ghRequest);
    }

    // private static GHResponse myRoute(Router router, GHRequest ghRequest, GraphHopperConfig ghc) {
    //     return router.route(ghRequest);
    // }

    private static GHResponse routeUnknownType(Object router, GHRequest ghRequest, GraphHopperConfig ghc) {
        if (router instanceof PtRouter) {
            return myRoute((PtRouter) router, ghRequest, ghc);
        // } else if (router instanceof Router) {
        //     return myRoute((Router) router, ghRequest, ghc);
        } else {
            return myRoute((GraphHopper) router, ghRequest, ghc);
        }
    }

    // @Inject private final PtRouter ptRouter;

    private static PtRouter myCreateRouter(GraphHopperGtfs hopperGtfs, GraphHopperConfig ghc) {
        // hopperGtfs = hopper;
        // GraphHopperGtfs hopperGtfs = hopper;
        GraphHopper hopper = (GraphHopper) hopperGtfs;
        BaseGraph baseGraph = hopper.getBaseGraph();
        if (baseGraph == null || !hopper.getFullyLoaded())
            throw new IllegalStateException("aDo a successful call to load or importOrLoad before routing");
        if (baseGraph.isClosed())
            throw new IllegalStateException("aYou need to create a new GraphHopper instance as it is already closed");
        if (hopper.getLocationIndex() == null)
            throw new IllegalStateException("aLocation index not initialized");

        // return doCreateRouter(baseGraph, encodingManager, locationIndex, profilesByName, pathBuilderFactory,
        //         trMap, routerConfig, createWeightingFactory(), chGraphs, landmarks);
        PtRouterImpl.Factory ptrif = new PtRouterImpl.Factory(
            ghc, 
            hopper.getTranslationMap(), 
            baseGraph, 
            hopper.getEncodingManager(), 
            hopper.getLocationIndex(),
            hopperGtfs.getGtfsStorage()
            );
        PtRouter ptri = ptrif.createWithoutRealtimeFeed();
        // System.out.println("router created");
        return ptri;
    }

    // protected static Router doCreateRouter(BaseGraph baseGraph, EncodingManager encodingManager, LocationIndex locationIndex, Map<String, Profile> profilesByName,
    //                                 PathDetailsBuilderFactory pathBuilderFactory, TranslationMap trMap, RouterConfig routerConfig,
    //                                 WeightingFactory weightingFactory, Map<String, RoutingCHGraph> chGraphs, Map<String, LandmarkStorage> landmarks) {
    //     return new Router(baseGraph, encodingManager, locationIndex, profilesByName, pathBuilderFactory,
    //             trMap, routerConfig, weightingFactory, chGraphs, landmarks
    //     );
    // }
    ///////

    public static long routing(Object router, GHRequest request, String profile, GraphHopperConfig ghc) {
        request.setProfile(profile).setLocale(Locale.UK);
    
        // GHResponse resp = hopper.route(request);
        GHResponse resp = routeUnknownType(router, request, ghc);
        // GHResponse resp = myRoute(router, request, ghc);
        // System.out.println("myroute done");
    
        // handle errors
        if (resp.hasErrors()) {
            List<Throwable> errors = resp.getErrors();
            if (errors.size() == 1 && errors.get(0) instanceof ConnectionNotFoundException) {
                // long[] ret = {-1L, -1L};
                long ret = -1L;
                return ret;
            } else if ((errors.size() == 1 || errors.size() == 2) && errors.get(0) instanceof PointNotFoundException) {
                // long[] ret = {-2L, -2L};
                long ret = -2L;
                return ret;
            } else {
                for (Throwable error : errors) {
                    error.printStackTrace();
                    if (error instanceof IllegalArgumentException) {
                        System.out.println("Illegal argument exception, desired profile is not in list of existing profiles");
                    }
                }
                // long[] ret = {-100L, -100L};
                // return ret;
                throw new RuntimeException(resp.getErrors().toString());
            }
        }
    // public static long[] routing(GraphHopper hopper, GHRequest request, String profile, GraphHopperConfig ghc) {
    //     request.setProfile(profile).setLocale(Locale.UK);
    
    //     // GHResponse resp = hopper.route(request);
    //     GHResponse resp = myRoute(hopper, request, ghc);
    //     System.out.println("myroute done");
    
    //     // handle errors
    //     if (resp.hasErrors()) {
    //         List<Throwable> errors = resp.getErrors();
    //         if (errors.size() == 1 && errors.get(0) instanceof ConnectionNotFoundException) {
    //             long[] ret = {-1L, -1L};
    //             return ret;
    //         } else if ((errors.size() == 1 || errors.size() == 2) && errors.get(0) instanceof PointNotFoundException) {
    //             long[] ret = {-2L, -2L};
    //             return ret;
    //         } else {
    //             for (Throwable error : errors) {
    //                 error.printStackTrace();
    //                 if (error instanceof IllegalArgumentException) {
    //                     System.out.println(hopper.getProfiles().toString());
    //                 }
    //             }
    //             // long[] ret = {-100L, -100L};
    //             // return ret;
    //             throw new RuntimeException(resp.getErrors().toString());
    //         }
    //     }
    
        ResponsePath path = resp.getBest();
        long timeInMs = path.getTime();
        InstructionList il = path.getInstructions();
        
        // long instructionTime = 0;
        // for (Instruction instruction : il) {
        //     // System.out.println("distance " + instruction.getDistance() + " for instruction: " + instruction.getTurnDescription(tr));
        //     instructionTime += instruction.getTime();
        // }
        // long[] ret = {timeInMs, instioructnTime};
        // return ret;

        return timeInMs;
    }

    /*
    public static void routing(GraphHopper hopper, List<GHRequest> requests, String profile) {
        for (GHRequest request : requests) {
            long[] mrr = routing(hopper, request, profile);
            System.out.println("Routed, time : " + mrr[0] + ", inst time: " + mrr[1]);
        }
    }

    public static void routing(GraphHopper hopper) {
        // simple configuration of the request object
        GHRequest req = new GHRequest(42.508552, 1.532936, 42.507508, 1.528773).
                // note that we have to specify which profile we are using even when there is only one like here
                        setProfile("car").
                // define the language for the turn instructions
                        setLocale(Locale.US);
        GHResponse rsp = hopper.route(req);

        // handle errors
        if (rsp.hasErrors())
            throw new RuntimeException(rsp.getErrors().toString());

        // use the best path, see the GHResponse class for more possibilities.
        ResponsePath path = rsp.getBest();

        // points, distance in meters and time in millis of the full path
        PointList pointList = path.getPoints();
        double distance = path.getDistance();
        long timeInMs = path.getTime();

        Translation tr = hopper.getTranslationMap().getWithFallBack(Locale.UK);
        InstructionList il = path.getInstructions();
        // iterate over all turn instructions
        for (Instruction instruction : il) {
            // System.out.println("distance " + instruction.getDistance() + " for instruction: " + instruction.getTurnDescription(tr));
        }
        assert il.size() == 6;
        assert Helper.round(path.getDistance(), -2) == 900;
    }
    */
    /*
    public static void speedModeVersusFlexibleMode(GraphHopper hopper) {
        GHRequest req = new GHRequest(42.508552, 1.532936, 42.507508, 1.528773).
                setProfile("car").setAlgorithm(Parameters.Algorithms.ASTAR_BI).putHint(Parameters.CH.DISABLE, true);
        GHResponse res = hopper.route(req);
        if (res.hasErrors())
            throw new RuntimeException(res.getErrors().toString());
        assert Helper.round(res.getBest().getDistance(), -2) == 900;
    }

    public static void headingAndAlternativeRoute(GraphHopper hopper) {
        // define a heading (direction) at start and destination
        GHRequest req = new GHRequest().setProfile("car").
                addPoint(new GHPoint(42.508774, 1.535414)).addPoint(new GHPoint(42.506595, 1.528795)).
                setHeadings(Arrays.asList(180d, 90d)).
                // use flexible mode (i.e. disable contraction hierarchies) to make heading and pass_through working
                        putHint(Parameters.CH.DISABLE, true);
        // if you have via points you can avoid U-turns there with
        // req.getHints().putObject(Parameters.Routing.PASS_THROUGH, true);
        GHResponse res = hopper.route(req);
        if (res.hasErrors())
            throw new RuntimeException(res.getErrors().toString());
        assert res.getAll().size() == 1;
        assert Helper.round(res.getBest().getDistance(), -2) == 800;

        // calculate alternative routes between two points (supported with and without CH)
        req = new GHRequest().setProfile("car").
                addPoint(new GHPoint(42.502904, 1.514714)).addPoint(new GHPoint(42.511953, 1.535914)).
                setAlgorithm(Parameters.Algorithms.ALT_ROUTE);
        req.getHints().putObject(Parameters.Algorithms.AltRoute.MAX_PATHS, 3);
        res = hopper.route(req);
        if (res.hasErrors())
            throw new RuntimeException(res.getErrors().toString());
        assert res.getAll().size() == 2;
        assert Helper.round(res.getBest().getDistance(), -2) == 2300;
    }

    public static void customizableRouting(String ghLoc) {
        GraphHopper hopper = new GraphHopper();
        hopper.setOSMFile(ghLoc);
        hopper.setGraphHopperLocation("target/routing-custom-graph-cache");
        hopper.setProfiles(new CustomProfile("car_custom").setCustomModel(new CustomModel()).setVehicle("car"));

        // The hybrid mode uses the "landmark algorithm" and is up to 15x faster than the flexible mode (Dijkstra).
        // Still it is slower than the speed mode ("contraction hierarchies algorithm") ...
        hopper.getLMPreparationHandler().setLMProfiles(new LMProfile("car_custom"));
        hopper.importOrLoad();

        // ... but for the hybrid mode we can customize the route calculation even at request time:
        // 1. a request with default preferences
        GHRequest req = new GHRequest().setProfile("car_custom").
                addPoint(new GHPoint(42.506472, 1.522475)).addPoint(new GHPoint(42.513108, 1.536005));

        GHResponse res = hopper.route(req);
        if (res.hasErrors())
            throw new RuntimeException(res.getErrors().toString());

        assert Math.round(res.getBest().getTime() / 1000d) == 96;

        // 2. now avoid primary roads and reduce maximum speed, see docs/core/custom-models.md for an in-depth explanation
        // and also the blog posts https://www.graphhopper.com/?s=customizable+routing
        CustomModel model = new CustomModel();
        model.addToPriority(If("road_class == PRIMARY", MULTIPLY, "0.5"));

        // unconditional limit to 100km/h
        model.addToPriority(If("true", LIMIT, "100"));

        req.setCustomModel(model);
        res = hopper.route(req);
        if (res.hasErrors())
            throw new RuntimeException(res.getErrors().toString());

        assert Math.round(res.getBest().getTime() / 1000d) == 165;
    }
    */
}
