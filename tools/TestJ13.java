package net.buildtheearth.terraplusplus.generator;
import java.util.*;
class TestJ13{
 record B(int x,int y,int z){}
 public static void main(String[] a){
  var samples=JenolanSurvey.SAMPLES;if(samples.size()<100)throw new AssertionError(samples.size());
  Set<B> air=new HashSet<>();Set<String> chunks=new HashSet<>();
  for(var p:samples){double x=JenolanSurvey.X+p.x(),z=JenolanSurvey.Z+p.z(),y=p.y();
   for(int bx=(int)Math.floor(x-2.4);bx<=Math.floor(x+2.4);bx++)for(int bz=(int)Math.floor(z-2.4);bz<=Math.floor(z+2.4);bz++)for(int by=(int)Math.floor(y-2);by<=Math.ceil(y+2);by++){
    boolean mouth=JenolanSurvey.mouth(bx+.5,bz+.5,p);if(!mouth&&by>-8||by>0)continue;
    double dx=(bx+.5-x)/2.4,dz=(bz+.5-z)/2.4,dy=(by+.5-y)/2;
    if(dx*dx+dy*dy+dz*dz<=1) air.add(new B(bx,by,bz));
   }
  }
  var q=new ArrayDeque<B>();var seen=new HashSet<B>();for(var b:air)if(b.y==0){if(Math.hypot(b.x+.5-JenolanSurvey.X,b.z+.5-JenolanSurvey.Z)>9)throw new AssertionError();seen.add(b);q.add(b);}
  int min=0;int[][] ds={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
  while(!q.isEmpty()){var b=q.remove();min=Math.min(min,b.y);chunks.add(Math.floorDiv(b.x,16)+","+Math.floorDiv(b.z,16));for(var d:ds){var n=new B(b.x+d[0],b.y+d[1],b.z+d[2]);if(air.contains(n)&&seen.add(n))q.add(n);}}
  if(min>-40||chunks.size()<2)throw new AssertionError("depth "+min+" chunks "+chunks.size());
  System.out.println(samples.size()+" samples; connected depth "+min+"; "+chunks.size()+" chunks in flat terrain fixture");
 }
}
