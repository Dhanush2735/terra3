package net.buildtheearth.terraplusplus.generator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Phase 3 survey centerlines, aligned offline to Terra3's projected block axes. */
final class SurveyedCaves {
    record Station(double x, double y, double z) {}
    record Route(String source, List<Station> stations, int reach) {}
    private record Entrance(int lat, int lon) {}
    private static final Map<Entrance, Route> ROUTES = load();

    static Route at(double lat, double lon) {
        return ROUTES.get(new Entrance((int)Math.round(lat*1e6),(int)Math.round(lon*1e6)));
    }

    private static Map<Entrance, Route> load() {
        try (InputStream in=SurveyedCaves.class.getResourceAsStream("/net/buildtheearth/terraplusplus/cave_surveys_v1.tsv")) {
            return in==null?Map.of():parse(in);
        } catch(IOException | IllegalArgumentException e) {
            System.getLogger(SurveyedCaves.class.getName()).log(System.Logger.Level.WARNING, "Phase 3 cave surveys rejected; using procedural caves", e);
            return Map.of();
        }
    }

    // One route per line: latE6<TAB>lonE6<TAB>provenance<TAB>x,y,z;x,y,z;...
    static Map<Entrance, Route> parse(InputStream in) throws IOException {
        var out=new HashMap<Entrance,Route>();
        try(var reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))) {
            for(String line;(line=reader.readLine())!=null;) {
                if(line.isBlank()||line.startsWith("#"))continue;
                String[] fields=line.split("\t",-1);
                if(fields.length!=4||fields[2].isBlank()||out.size()>=64)throw new IOException("Invalid cave survey row");
                var key=new Entrance(Integer.parseInt(fields[0]),Integer.parseInt(fields[1]));
                if(Math.abs((long)key.lat)>90_000_000||Math.abs((long)key.lon)>180_000_000)throw new IOException("Invalid entrance");
                var points=new ArrayList<Station>();
                double reach=0,total=0;
                for(String token:fields[3].split(";",-1)) {
                    String[] xyz=token.split(",",-1);
                    if(xyz.length!=3||points.size()>=2048)throw new IOException("Invalid station");
                    var p=new Station(Double.parseDouble(xyz[0]),Double.parseDouble(xyz[1]),Double.parseDouble(xyz[2]));
                    if(!Double.isFinite(p.x)||!Double.isFinite(p.y)||!Double.isFinite(p.z)
                            ||Math.abs(p.x)>3500||Math.abs(p.z)>3500||p.y> -10||p.y< -3500)throw new IOException("Station outside pilot bounds");
                    if(points.isEmpty()&&(p.x!=0||p.z!=0||p.y< -64))throw new IOException("Route must start below its matched entrance");
                    if(!points.isEmpty()) {
                        var q=points.get(points.size()-1);
                        total+=Math.hypot(Math.hypot(p.x-q.x,p.z-q.z),p.y-q.y);
                    }
                    reach=Math.max(reach,Math.max(Math.abs(p.x),Math.abs(p.z)));
                    points.add(p);
                }
                if(points.size()<2||total<=0||total>5000||out.putIfAbsent(key,new Route(fields[2],List.copyOf(points),(int)Math.ceil(reach)+4))!=null)
                    throw new IOException("Invalid route length or duplicate entrance");
            }
        }
        return Map.copyOf(out);
    }
}
