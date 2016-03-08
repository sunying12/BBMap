package align2;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

import stream.ConcurrentGenericReadInputStream;
import stream.ConcurrentReadStreamInterface;
import stream.FASTQ;
import stream.Read;

import dna.Data;
import dna.Timer;
import fileIO.ReadWrite;
import fileIO.FileFormat;
import fileIO.TextStreamWriter;

/**
 * @author Brian Bushnell
 * @date Nov 1, 2012
 *
 */
public class SortReadsByID {
	
	public static void main(String[] args){
		
		String in1=null;
		String in2=null;
		String out="raw_idsorted#.txt.gz";
		
		for(int i=0; i<args.length; i++){
			final String arg=args[i];
			final String[] split=arg.split("=");
			String a=split[0].toLowerCase();
			String b=split.length>1 ? split[1] : null;
			if("null".equalsIgnoreCase(b)){b=null;}
//			System.err.println("Processing "+args[i]);
			
			if(arg.startsWith("-Xmx") || arg.startsWith("-Xms") || arg.equals("-ea") || arg.equals("-da")){
				//jvm argument; do nothing
			}else if(a.equals("i") || a.equals("in") || a.equals("input") || a.equals("in1") || a.equals("input1")){
				in1=b;
				if(b.indexOf('#')>=0){
					in1=b.replaceFirst("#", "1");
					in2=b.replaceFirst("#", "2");
				}
			}else if(a.equals("in2") || a.equals("input2")){
				in2=b;
			}else if(a.equals("o") || a.equals("out") || a.equals("output")){
				out=b;
			}else if(a.endsWith("parsecustom")){
				FASTQ.PARSE_CUSTOM=Tools.parseBoolean(b);
				Data.sysout.println("Set FASTQ.PARSE_CUSTOM to "+FASTQ.PARSE_CUSTOM);
			}else if(a.endsWith("renumber")){
				RENUMBER=Tools.parseBoolean(b);
			}else if(a.equals("ziplevel") || a.equals("zl")){
				ReadWrite.ZIPLEVEL=Integer.parseInt(b);
			}else if(a.equals("testinterleaved")){
				FASTQ.TEST_INTERLEAVED=Tools.parseBoolean(b);
				Data.sysout.println("Set TEST_INTERLEAVED to "+FASTQ.TEST_INTERLEAVED);
			}else if(a.equals("forceinterleaved")){
				FASTQ.FORCE_INTERLEAVED=Tools.parseBoolean(b);
				Data.sysout.println("Set FORCE_INTERLEAVED to "+FASTQ.FORCE_INTERLEAVED);
			}else if(a.equals("interleaved") || a.equals("int")){
				if("auto".equalsIgnoreCase(b)){FASTQ.FORCE_INTERLEAVED=!(FASTQ.TEST_INTERLEAVED=true);}
				else{
					FASTQ.FORCE_INTERLEAVED=FASTQ.TEST_INTERLEAVED=Tools.parseBoolean(b);
					Data.sysout.println("Set INTERLEAVED to "+FASTQ.FORCE_INTERLEAVED);
				}
			}else if(a.equals("overwrite") || a.equals("ow")){
				OVERWRITE=Tools.parseBoolean(b);
				Data.sysout.println("Set OVERWRITE to "+OVERWRITE);
			}else if(a.endsWith("blocksize")){
				BLOCKSIZE=Integer.parseInt(b);
			}else{
				throw new RuntimeException("Unknown parameter: "+args[i]);
			}
		}
		
		if(in1==null){throw new RuntimeException("Please specify input file.");}
		if(out==null){throw new RuntimeException("Please specify output file.");}
		if(in1.equalsIgnoreCase(in2) || in1.equalsIgnoreCase(out) || (in2!=null && in2.equalsIgnoreCase(out))){
			throw new RuntimeException("Duplicate filenames.");
		}

		if(out!=null && !out.contains("#")){
			throw new RuntimeException("Output filename must contain '#' symbol.");
		}
		
		SortReadsByID srid=new SortReadsByID(in1, in2, out);
		srid.process();
	}
	
	
	public void process(){

		Timer tRead=new Timer();
		Timer tSort=new Timer();
		Timer tAll=new Timer();
		
		tRead.start();
		tAll.start();
		
		final long maxReads=-1;
		ConcurrentReadStreamInterface cris;
		{
			FileFormat ff1=FileFormat.testInput(in1, FileFormat.FASTQ, null, true, true);
			FileFormat ff2=FileFormat.testInput(in2, FileFormat.FASTQ, null, true, true);
			cris=ConcurrentGenericReadInputStream.getReadInputStream(maxReads, false, true, ff1, ff2);
			Thread th=new Thread(cris);
			th.start();
		}
		
		HashMap<Integer, Block> map=new HashMap<Integer, Block>();
		
		{
			ListNum<Read> ln=cris.nextList();
			ArrayList<Read> reads=(ln!=null ? ln.list : null);

			while(reads!=null && reads.size()>0){
				//System.err.println("reads.size()="+reads.size());
				for(Read r : reads){

					int bin=(int)(r.numericID/BLOCKSIZE);
					Block b=map.get(bin);
					if(b==null){
						String o1=out.replaceFirst("#", "_bin"+bin+"_1");
						String o2=(cris.paired() && !OUT_INTERLEAVED) ? out.replaceFirst("#", "_bin"+bin+"_2") : null;
						b=new Block(o1, o2);
						map.put(bin, b);
					}
					b.add(r);
				}
				//System.err.println("returning list");
				cris.returnList(ln, ln.list.isEmpty());
				//System.err.println("fetching list");
				ln=cris.nextList();
				reads=(ln!=null ? ln.list : null);
			}
			
			cris.returnList(ln, ln.list.isEmpty());
			ReadWrite.closeStream(cris);
		}
		
		for(Block b : map.values()){b.close();}
		
		tRead.stop();
		Data.sysout.println("Read time:   \t"+tRead);
		tSort.start();
		
		String o1=out.replaceFirst("#", "1");
		String o2=(cris.paired() && !OUT_INTERLEAVED) ? out.replaceFirst("#", "2") : null;
		Block sorted=new Block(o1, o2);
		
		long count=0;
		
		ArrayList<Integer> keys=new ArrayList<Integer>();
		keys.addAll(map.keySet());
		Collections.sort(keys);
		for(Integer key : keys){
			Block b=map.get(key);
			b.join();
			map.remove(key);
			{
				FileFormat ff1=FileFormat.testInput(b.out1, FileFormat.FASTQ, null, true, true);
				FileFormat ff2=FileFormat.testInput(b.out2, FileFormat.FASTQ, null, true, true);
				cris=ConcurrentGenericReadInputStream.getReadInputStream(maxReads, false, true, ff1, ff2);
				Thread th=new Thread(cris);
				th.start();
			}
			ArrayList<Read> reads2=new ArrayList<Read>((int)b.count);
			count+=b.count;
			
			{
				ListNum<Read> ln=cris.nextList();
				ArrayList<Read> reads=(ln!=null ? ln.list : null);

				while(reads!=null && reads.size()>0){
					reads2.addAll(reads);
					//System.err.println("returning list");
					cris.returnList(ln, ln.list.isEmpty());
					//System.err.println("fetching list");
					ln=cris.nextList();
					reads=(ln!=null ? ln.list : null);
				}
				
				cris.returnList(ln, ln.list.isEmpty());
				ReadWrite.closeStream(cris);
			}
			
			Collections.sort(reads2, idComparator);
			for(Read r : reads2){sorted.add(r);}
			new File(b.out1).delete();
			if(b.out2!=null){new File(b.out2).delete();}
		}
		
		sorted.close();
		sorted.join();
		
		tSort.stop();
		tAll.stop();
		
		Data.sysout.println("Total reads: \t"+count);
		Data.sysout.println("Sort time:   \t"+tSort);
		Data.sysout.println("Total time:  \t"+tAll);
		
	}
	
	/**
	 * @param in1
	 * @param in2
	 * @param out
	 */
	public SortReadsByID(String in1_, String in2_, String out_) {
		in1=in1_;
		in2=in2_;
		out=out_;
		
		FileFormat ff=FileFormat.testOutput(out, FileFormat.BREAD, null, true, false, false);
		outFastq=ff.fastq();
		outFasta=ff.fasta();
		outText=ff.bread();
	}

	public String in1;
	public String in2;
	public String out;

	private final boolean outText;
	private final boolean outFasta;
	private final boolean outFastq;
	
	public static int BLOCKSIZE=8000000;
	public static boolean OVERWRITE=true;
	public static boolean RENUMBER=false;
	public static boolean OUT_INTERLEAVED=false;
	
	private class Block{
		
		public Block(String out1_, String out2_){
			out1=out1_;
			out2=out2_;
			
			tsw1=new TextStreamWriter(out1, OVERWRITE, false, false);
			tsw2=(out2==null ? null : new TextStreamWriter(out2, OVERWRITE, false, false));
			
			tsw1.start();
			if(tsw2!=null){tsw2.start();}
		}
		
		public void add(Read r){
			count++;
			Read r2=r.mate;
			
			StringBuilder sb1=outText ? r.toText(true) : outFastq ? r.toFastq() : outFasta ? r.toFasta() : null;
			StringBuilder sb2=r2==null ? null : outText ? r2.toText(true) : outFastq ? r2.toFastq() : outFasta ? r2.toFasta() : null;
			
			tsw1.print(sb1.append('\n'));
			if(sb2!=null){
				if(tsw2!=null){
					tsw2.print(sb2.append('\n'));
				}else{
					tsw1.print(sb2.append('\n')); //Interleaved
				}
			}
			
		}
		
		public void close(){
			tsw1.poison();
			if(tsw2!=null){tsw2.poison();}
		}
		
		public void join(){
			tsw1.waitForFinish();
			if(tsw2!=null){tsw2.waitForFinish();}
		}
		
		String out1;
		String out2;
		
		TextStreamWriter tsw1;
		TextStreamWriter tsw2;
		
		long count=0;
		
	}
	
	public static final class ReadComparatorID implements Comparator<Read>{
		
		@Override
		public int compare(Read r1, Read r2) {
			if(r1.numericID<r2.numericID){return -1;}
			else if(r1.numericID>r2.numericID){return 1;}
			
			if(!r1.id.equals(r2.id)){return r1.id.compareTo(r2.id);}
			return 0;
		}
		
	}
	public static final ReadComparatorID idComparator=new ReadComparatorID();
	
	
}