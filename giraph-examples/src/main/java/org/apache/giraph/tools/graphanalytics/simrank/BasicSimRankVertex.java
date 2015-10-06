package org.apache.giraph.tools.graphanalytics.simrank;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.giraph.aggregators.DoubleSumAggregator;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.edge.EdgeFactory;
import org.apache.giraph.examples.Algorithm;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.io.formats.AdjacencyListTextVertexInputFormat;
import org.apache.giraph.io.formats.TextVertexOutputFormat;
import org.apache.giraph.master.DefaultMasterCompute;
import org.apache.giraph.tools.utils.RandomWalksWithMeetPoints;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.log4j.Logger;

import com.google.common.collect.Lists;


@Algorithm(
		name = "basicSimRank",
		description = "compute s(u,v) based on Random Walk model."
)
public class BasicSimRankVertex extends Vertex<IntWritable, DoubleWritable, NullWritable, RandomWalksWithMeetPoints>{

	private static Logger LOG = Logger.getLogger(BasicSimRankVertex.class);
	private static double decayFactor = 0.8;
	private int MAX_ITERATION_NUM = 10000000;
	
	@Override
	public void compute(Iterable<RandomWalksWithMeetPoints> messages)
			throws IOException {
		if(getSuperstep() > MAX_ITERATION_NUM){
			voteToHalt();
			return ;
		}
		
		if(getSuperstep() == 0){
			if(getId().get() == this.getConf().getInt("simrank.src", 10)){
				this.sendMessageToAllEdges(new RandomWalksWithMeetPoints(true, 1.0 / getNumEdges()));
			}
			else if(getId().get() == this.getConf().getInt("simrank.dst", 100)){
				this.sendMessageToAllEdges(new RandomWalksWithMeetPoints(false, 1.0 / getNumEdges()));
			}
			MAX_ITERATION_NUM = this.getConf().getInt("simrank.maxiter", 10);
		}
		else{
			ArrayList<RandomWalksWithMeetPoints> srcMsg = new ArrayList<RandomWalksWithMeetPoints>();
			double dstProb = 0.0;
			/** 1. merge the src/dst message */
			for(RandomWalksWithMeetPoints msg : messages){
				if(msg.isSrcProb()){
					boolean isMerged = false;
					for(int i = 0; i < srcMsg.size(); ++i){
						if(srcMsg.get(i).equal(msg)){
							srcMsg.get(i).merge(msg); /*O(meetPoints.size())*/
							isMerged = true;
							break;
						}
					}
					if(!isMerged){
						RandomWalksWithMeetPoints rwmp = new RandomWalksWithMeetPoints();
						rwmp.copy(msg);
						srcMsg.add(rwmp);
					}
				}
				else{
					dstProb += msg.getProb();
				}
//				LOG.info("vid="+getId()+": "+msg.toString()+" srcMsgSize="+srcMsg.size());
			}
			
			/** 2. second calculate the first meeting probability at this vertex */
			double firstMeetProb = 0.0;
//			ArrayList<RandomWalksWithMeetPoints> newSrcMsg = new ArrayList<RandomWalksWithMeetPoints>();
//			ArrayList<RandomWalksWithMeetPoints> oldSrcMsg = new ArrayList<RandomWalksWithMeetPoints>();
			boolean isNewMessage = false;
			for(int i = 0; i < srcMsg.size(); ++i){
				double dupProb = 0.0;
				for(int j = 0; j < srcMsg.size(); ++j){
					dupProb += srcMsg.get(i).getLastMeetPoint(srcMsg.get(j));
				}
				firstMeetProb += srcMsg.get(i).getProb() * (dstProb - dupProb);
//				LOG.info("Msg#"+i+": dupProb="+dupProb+" dstProb="+dstProb
//						+" vid="+getId().get()+" srcProb="+srcMsg.get(i).getProb()
//						+" firstMeetProb="+firstMeetProb);
				if(dstProb > dupProb){
					/* new meet point for this path */
//					newSrcMsg.add(srcMsg.get(i));
					isNewMessage = true;
				}
//				else{
//					oldSrcMsg.add(srcMsg.get(i));
//				}
			}
			long step = getSuperstep();
			for(int i = 1; i <= step; i++){
				firstMeetProb *= decayFactor;
			}
			setValue(new DoubleWritable(this.getValue().get()+firstMeetProb));
			
//			LOG.info("New src msg size=" + newSrcMsg.size());

			/** 3. third send updated messages */
			double degree = this.getNumEdges();
			if(dstProb > 0)
				sendMessageToAllEdges(new RandomWalksWithMeetPoints(false, dstProb / degree));
			if(isNewMessage){
				for(int i = 0; i < srcMsg.size(); ++i){
					srcMsg.get(i).addMeetPoint(getId().get(), dstProb/srcMsg.size(), (int)getSuperstep());
					srcMsg.get(i).updateProb(degree);
//					LOG.info("i="+i+": newSrcMsg="+srcMsg.size()
//						+"\n\tSendNewMessage="+srcMsg.get(i).toString());
					sendMessageToAllEdges(srcMsg.get(i));
				}
			}
			else{
				for(int i = 0; i < srcMsg.size(); ++i){
					srcMsg.get(i).updateProb(degree);
//					LOG.info("SendOldMessage="+srcMsg.get(i).toString());
					sendMessageToAllEdges(srcMsg.get(i));
				}
			}
//			for(int i = 0; i < newSrcMsg.size(); ++i){
//				long multiple = newSrcMsg.get(i).getMultiple();
//				newSrcMsg.get(i).addMeetPoint(getId().get(), dstProb/newSrcMsg.size(), (int)getSuperstep());
//				newSrcMsg.get(i).setMultiple(newSrcMsg.size() * multiple);
//				newSrcMsg.get(i).updateProb(degree);
//				LOG.info("i="+i+": newSrcMsg="+newSrcMsg.size()+" multiple="+multiple+" new multiple="+ newSrcMsg.get(i).getMultiple()
//						+"\n\tSendNewMessage="+newSrcMsg.get(i).toString());
//				sendMessageToAllEdges(newSrcMsg.get(i));
//			}
//			for(int i = 0; i < oldSrcMsg.size(); ++i){
//				oldSrcMsg.get(i).updateProb(degree);
//				LOG.info("SendOldMessage="+oldSrcMsg.get(i).toString());
//				sendMessageToAllEdges(oldSrcMsg.get(i));
//			}
			aggregate("aggregate.tmppairsimrank", new DoubleWritable(firstMeetProb));
			aggregate("aggregate.pairsimrank", new DoubleWritable(getValue().get()));
		}
	}

	/** Master compute which uses aggregators. */
	public static class AggregatorsMasterCompute extends
	      DefaultMasterCompute {
	    @Override
	    public void compute() {
			double simrank = ((DoubleWritable)getAggregatedValue("aggregate.pairsimrank")).get();
			double tmpsimrank = ((DoubleWritable)getAggregatedValue("aggregate.tmppairsimrank")).get();
			System.out.println("step= "+getSuperstep()+": simrank="+simrank+" tmpsimrank="+tmpsimrank);
	    }

	    @Override
	    public void initialize() throws InstantiationException,
	        IllegalAccessException {
	      registerAggregator("aggregate.pairsimrank", DoubleSumAggregator.class);
	      registerAggregator("aggregate.tmppairsimrank", DoubleSumAggregator.class);
	    }
	  }
	  
	/** Vertex InputFormat */
	public static class BasicSimRankVertexInputFormat extends
		AdjacencyListTextVertexInputFormat<IntWritable, DoubleWritable, NullWritable> {
			/** Separator for id and value */
			private static final Pattern SEPARATOR = Pattern.compile("[\t ]");

			@Override
			public AdjacencyListTextVertexReader createVertexReader(
					InputSplit split, TaskAttemptContext context) {
				return new OrderedGraphReader();
			}

			public class  OrderedGraphReader extends AdjacencyListTextVertexReader {

				protected String[] preprocessLine(Text line) throws IOException {
					String[] values = SEPARATOR.split(line.toString());
					return values;
				}

				@Override
				protected IntWritable getId(String[] values) throws IOException {
					return decodeId(values[0]);
				}

				@Override
				protected DoubleWritable getValue(String[] values) throws IOException {
					return decodeValue(null);
				}

				@Override
				protected Iterable<Edge<IntWritable, NullWritable>> getEdges(String[] values) throws
				IOException {
					int i = 1;
					List<Edge<IntWritable, NullWritable>> edges = Lists.newLinkedList();
					while (i < values.length) {
						int target = Integer.valueOf(values[i]);
						edges.add(EdgeFactory.create(new IntWritable(target), NullWritable.get()));
						i++;
					}
					return edges;
				}

				@Override
				public IntWritable decodeId(String s) {
					return new IntWritable(Integer.valueOf(s));
				}

				@Override
				public DoubleWritable decodeValue(String s) {
					return new DoubleWritable(0);
				}

				@Override
				public Edge<IntWritable, NullWritable> decodeEdge(String id,
						String value) {
					return null;
				}
			}
		} 

	public static class BasicSimRankVertexOutputFormat extends
		TextVertexOutputFormat<IntWritable, DoubleWritable, NullWritable> {
			@Override
			public TextVertexWriter createVertexWriter(TaskAttemptContext context)
			throws IOException, InterruptedException {
			return new OrderedGraphWriter();
			}
			
			public class OrderedGraphWriter extends TextVertexWriter {
				@Override
				public void writeVertex(
						Vertex<IntWritable, DoubleWritable, NullWritable, ?> vertex)
				throws IOException, InterruptedException {
					getRecordWriter().write(
						new Text(vertex.getId().toString()),
						new Text(vertex.getValue().toString()));
				}
		}
	}
}
