package org.x9s26u.rcam;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.Matrix;
import android.media.ExifInterface;
import java.io.*;
import java.util.Arrays;

/** Optional JPEG-domain processing, not a sensor RAW ISP. Memory is bounded to a few scanlines. */
final class NaturalProcessing {
    private NaturalProcessing() {}
    private static int clip(float v) { return Math.max(0,Math.min(255,Math.round(v))); }
    private static int channel(int p,int shift){return (p>>shift)&255;}
    private static int luma(int p){return (77*channel(p,16)+150*channel(p,8)+29*channel(p,0)+128)>>8;}
    static void denoise(Bitmap b,int iso) {
        int w=b.getWidth(),h=b.getHeight(); if(w<3||h<3)return;
        int[] prev=new int[w],row=new int[w],next=new int[w],out=new int[w];
        b.getPixels(prev,0,w,0,0,w,1); b.getPixels(row,0,w,0,1,w,1);
        float sigma=Math.max(5,Math.min(18,5+(float)Math.sqrt(Math.max(100,iso)/100f)*3));
        float[] range=new float[256];for(int i=0;i<256;i++)range[i]=(float)Math.exp(-i*i/(2*sigma*sigma));
        for(int y=1;y<h-1;y++){
            b.getPixels(next,0,w,0,y+1,w,1);out[0]=row[0];out[w-1]=row[w-1];
            for(int x=1;x<w-1;x++){
                int p=row[x], l=luma(p); float rs=0,gs=0,bs=0,ws=0;
                for(int dy=-1;dy<=1;dy++){int[] scan=dy<0?prev:dy==0?row:next;
                    for(int dx=-1;dx<=1;dx++){int q=scan[x+dx];int diff=Math.abs(luma(q)-l);
                        float wt=range[diff]*(dx==0&&dy==0?2f:dx==0||dy==0?1f:.55f);
                        rs+=wt*channel(q,16);gs+=wt*channel(q,8);bs+=wt*channel(q,0);ws+=wt;}}
                // Preserve most luminance structure, smooth chroma more strongly; no unsharp mask.
                float r=rs/ws,g=gs/ws,bl=bs/ws;float ly=.299f*r+.587f*g+.114f*bl;
                float yy=l*.65f+ly*.35f;
                out[x]=0xff000000|(clip(yy+(r-ly)*.75f+(channel(p,16)-l)*.25f)<<16)|(clip(yy+(g-ly)*.75f+(channel(p,8)-l)*.25f)<<8)|clip(yy+(bl-ly)*.75f+(channel(p,0)-l)*.25f);
            }
            b.setPixels(out,0,w,0,y,w,1);int[] tmp=prev;prev=row;row=next;next=tmp;
        }
    }
    static void applyMatrix(Bitmap b,ColorMatrix matrix){
        float[] m=matrix.getArray();int w=b.getWidth();int[] row=new int[w];
        for(int y=0;y<b.getHeight();y++){b.getPixels(row,0,w,0,y,w,1);for(int x=0;x<w;x++){
            int p=row[x];int r=channel(p,16),g=channel(p,8),bl=channel(p,0);
            row[x]=0xff000000|(clip(m[0]*r+m[1]*g+m[2]*bl+m[3]*255+m[4])<<16)|(clip(m[5]*r+m[6]*g+m[7]*bl+m[8]*255+m[9])<<8)|clip(m[10]*r+m[11]*g+m[12]*bl+m[13]*255+m[14]);
        }b.setPixels(row,0,w,0,y,w,1);}
    }
    static final class Cube {
        final int size; final float[] values;
        Cube(int n,float[] v){size=n;values=v;}
        static Cube read(AssetManager assets,String file)throws IOException{
            int n=0, count=0;float[] v=null;
            try(BufferedReader r=new BufferedReader(new InputStreamReader(assets.open(file)))){String line;
                while((line=r.readLine())!=null){line=line.trim();if(line.isEmpty()||line.startsWith("#")||line.startsWith("TITLE"))continue;
                    String[] t=line.split("\\s+");if(t[0].equals("LUT_3D_SIZE")){n=Integer.parseInt(t[1]);if(n<2||n>65)throw new IOException("Invalid cube size");v=new float[n*n*n*3];continue;}
                    if(t[0].equals("DOMAIN_MIN")||t[0].equals("DOMAIN_MAX")){float expected=t[0].endsWith("MIN")?0:1;for(int j=1;j<4;j++)if(Float.parseFloat(t[j])!=expected)throw new IOException("Unsupported LUT domain");continue;}
                    if(v==null||t.length!=3||count+3>v.length)throw new IOException("Invalid LUT row");for(String x:t){float z=Float.parseFloat(x);if(!Float.isFinite(z))throw new IOException("Nonfinite LUT");v[count++]=z;}
                }}if(v==null||count!=v.length)throw new IOException("Incomplete LUT");return new Cube(n,v);
        }
        private float at(int r,int g,int b,int c){return values[3*(r+size*(g+size*b))+c];}
        void map(float[] rgb){float rx=rgb[0]*(size-1),gx=rgb[1]*(size-1),bx=rgb[2]*(size-1);int r=Math.min(size-2,(int)rx),g=Math.min(size-2,(int)gx),b=Math.min(size-2,(int)bx);float dr=rx-r,dg=gx-g,db=bx-b;
            for(int c=0;c<3;c++){float x0=at(r,g,b,c)*(1-dr)+at(r+1,g,b,c)*dr,x1=at(r,g+1,b,c)*(1-dr)+at(r+1,g+1,b,c)*dr,x2=at(r,g,b+1,c)*(1-dr)+at(r+1,g,b+1,c)*dr,x3=at(r,g+1,b+1,c)*(1-dr)+at(r+1,g+1,b+1,c)*dr;rgb[c]=Math.max(0,Math.min(1,(x0*(1-dg)+x1*dg)*(1-db)+(x2*(1-dg)+x3*dg)*db));}}
    }
    static void applyLuts(Bitmap b,AssetManager assets)throws IOException{
        Cube color=Cube.read(assets,"LUT_17/color_base_SRGB.cube"),tone=Cube.read(assets,"LUT_17/tone_base_SRGB.cube");int w=b.getWidth();int[] row=new int[w];float[] rgb=new float[3];
        for(int y=0;y<b.getHeight();y++){b.getPixels(row,0,w,0,y,w,1);for(int x=0;x<w;x++){int p=row[x];rgb[0]=channel(p,16)/255f;rgb[1]=channel(p,8)/255f;rgb[2]=channel(p,0)/255f;color.map(rgb);tone.map(rgb);row[x]=0xff000000|(clip(rgb[0]*255)<<16)|(clip(rgb[1]*255)<<8)|clip(rgb[2]*255);}b.setPixels(row,0,w,0,y,w,1);}
    }
    static byte[] copyExif(byte[] original,byte[] edited,int width,int height,File cache)throws IOException{
        ExifInterface from=new ExifInterface(new ByteArrayInputStream(original));File f=File.createTempFile("opticore-edit-",".jpg",cache);
        try{try(FileOutputStream out=new FileOutputStream(f)){out.write(edited);}ExifInterface to=new ExifInterface(f.getAbsolutePath());
            String[] tags={"Make","Model","DateTime","DateTimeOriginal","DateTimeDigitized","ExposureTime","FNumber","PhotographicSensitivity","ISOSpeedRatings","FocalLength","FocalLengthIn35mmFilm","WhiteBalance","Flash","Orientation","ExposureBiasValue","LensModel","ColorSpace","SubSecTimeOriginal","OffsetTimeOriginal"};
            for(String tag:tags){String value=from.getAttribute(tag);if(value!=null)to.setAttribute(tag,value);}
            to.setAttribute("ImageWidth",Integer.toString(width));to.setAttribute("ImageLength",Integer.toString(height));to.setAttribute("PixelXDimension",Integer.toString(width));to.setAttribute("PixelYDimension",Integer.toString(height));to.saveAttributes();
            try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return out.toByteArray();}
        }finally{f.delete();}
    }
    static Bitmap orient(Bitmap b,int o){Matrix m=new Matrix();switch(o){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(-90);m.postScale(-1,1);break;case 8:m.setRotate(-90);break;default:return b;}return Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);}
}
