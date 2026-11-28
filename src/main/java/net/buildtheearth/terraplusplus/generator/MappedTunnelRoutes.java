package net.buildtheearth.terraplusplus.generator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline-vetted tunnel routes. OSM plan lines alone do not qualify. */
final class MappedTunnelRoutes {
    record Point(double x,double y,double z) {}
    record Leg(Point from,Point to,Point entrance,Point exit) {}
    private static final Map<Long,List<Leg>> CHUNKS=load();

    static List<Leg> forChunk(int x,int z){return CHUNKS.getOrDefault(key(x,z),List.of());}
    private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private static int chunk(double block){return Math.floorDiv((int)Math.floor(block),16);}
    private static Map<Long,List<Leg>> load(){
        try(InputStream in=MappedTunnelRoutes.class.getResourceAsStream("/net/buildtheearth/terraplusplus/mapped_tunnels_v1.tsv")){
            return in==null?Map.of():parse(in);
        }catch(IOException|IllegalArgumentException ex){
            System.getLogger(MappedTunnelRoutes.class.getName()).log(System.Logger.Level.WARNING,"Mapped tunnel pilot data rejected",ex);
            return Map.of();
        }
    }

    // provenance<TAB>x,y,z;x,y,z;... (absolute projected block coordinates)
    static Map<Long,List<Leg>> parse(InputStream in)throws IOException{
        var index=new HashMap<Long,List<Leg>>();
        int count=0;
        try(var reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            for(String line;(line=reader.readLine())!=null;){
                if(line.isBlank()||line.startsWith("#"))continue;
                var fields=line.split("\t",-1);
                if(fields.length!=2||fields[0].isBlank()||++count>32)throw new IOException("Invalid tunnel record");
                var nodes=fields[1].split(";",-1);
                if(nodes.length<2||nodes.length>2048)throw new IOException("Invalid station count");
                var points=new ArrayList<Point>(nodes.length);
                for(String node:nodes){
                    var xyz=node.split(",",-1);
                    if(xyz.length!=3)throw new IOException("Invalid station");
                    var p=new Point(Double.parseDouble(xyz[0]),Double.parseDouble(xyz[1]),Double.parseDouble(xyz[2]));
                    if(!Double.isFinite(p.x)||!Double.isFinite(p.y)||!Double.isFinite(p.z)
                            ||Math.abs(p.x)>30_000_000||Math.abs(p.z)>30_000_000||Math.abs(p.y)>2031)
                        throw new IOException("Tunnel station outside world bounds");
                    points.add(p);
                }
                Point entrance=points.get(0),exit=points.get(points.size()-1);
                Point previous=null;
                for(Point p:points){
                    if(previous!=null){
                        double distance=Math.hypot(Math.hypot(p.x-previous.x,p.z-previous.z),p.y-previous.y);
                        if(distance<=0||distance>64)throw new IOException("Densify tunnel legs to 64 blocks or shorter");
                        var leg=new Leg(previous,p,entrance,exit);
                        for(int x=chunk(Math.min(p.x,previous.x)-4);x<=chunk(Math.max(p.x,previous.x)+4);x++)
                            for(int z=chunk(Math.min(p.z,previous.z)-4);z<=chunk(Math.max(p.z,previous.z)+4);z++)
                                index.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(leg);
                    }
                    previous=p;
                }
            }
        }
        index.replaceAll((k,v)->List.copyOf(v));
        return Map.copyOf(index);
    }
}
