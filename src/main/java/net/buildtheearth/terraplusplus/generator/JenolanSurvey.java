package net.buildtheearth.terraplusplus.generator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One surveyed pilot, independent of the worldwide procedural entrance index. */
final class JenolanSurvey {
    // J13, MGA/GDA94 zone 56 E224095.85 N6255375.96, projected to PRIME.
    static final double X=19908902.899, Z=-2162761.694;
    record Point(double x,double y,double z) {}
    static final List<Point> SAMPLES=load();

    static boolean near(int minX,int minZ){
        return X>=minX-64&&X<=minX+79&&Z>=minZ-64&&Z<=minZ+79;
    }

    static boolean mouth(double x,double z,Point p){
        return p.y>=-8&&Math.hypot(x-X,z-Z)<=9;
    }

    private static List<Point> load(){
        try(var in=JenolanSurvey.class.getResourceAsStream("/net/buildtheearth/terraplusplus/jenolan_j13.csv")){
            return in==null?List.of():parse(in);
        }catch(IOException|IllegalArgumentException e){return List.of();}
    }

    static List<Point> parse(InputStream in) throws IOException{
        var result=new ArrayList<Point>();
        try(var reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            Point prev=null;
            int count=0;
            for(String line;(line=reader.readLine())!=null;){
                if(line.isBlank()||line.startsWith("#"))continue;
                String[] a=line.split(",",-1);
                if(a.length!=3||++count>64)throw new IOException("Invalid J13 station");
                var p=new Point(Double.parseDouble(a[0]),Double.parseDouble(a[1]),Double.parseDouble(a[2]));
                if(!(Math.abs(p.x)<=61&&Math.abs(p.z)<=61&&p.y<=0&&p.y>=-100))throw new IOException("Invalid J13 bounds");
                if(prev==null){
                    if(p.x!=0||p.y!=0||p.z!=0)throw new IOException("Missing J13 entrance");
                    result.add(p);
                }else{
                    double dx=p.x-prev.x,dy=p.y-prev.y,dz=p.z-prev.z;
                    double length=Math.hypot(Math.hypot(dx,dz),dy);
                    if(!(length>0&&length<=32))throw new IOException("Invalid J13 leg");
                    int steps=(int)Math.ceil(length*2);
                    for(int i=1;i<=steps;i++){
                        double t=(double)i/steps;
                        result.add(new Point(prev.x+t*dx,prev.y+t*dy,prev.z+t*dz));
                    }
                }
                prev=p;
            }
            if(count<2)throw new IOException("Missing J13 passage");
        }
        return List.copyOf(result);
    }
}
