package org.apache.giraph.tools.graphanalytics.simrank;

import java.io.IOException;
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
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.log4j.Logger;


import com.google.common.collect.Lists;

@Algorithm(
		name = "Random Walks based Approximating SimRank",
		description = "compute s(u,v) based on Random Walk without first-meeting gaurantee "
)
public class RWApproximationVertex 
extends Vertex<IntWritable, DoubleWritable, NullWritable, DoubleWritable>{

	private static Logger LOG = Logger.getLogger(RWApproximationVertex.class);
	private static double decayFactor = 0.8;
	private int MAX_ITERATION_NUM = 10000000;
	
	@Override
	public void compute(Iterable<DoubleWritable> messages)
			throws IOException {
		if(getSuperstep() > MAX_ITERATION_NUM){
			voteToHalt();
			return ;
		}
		
		if(getSuperstep() == 0){
			/* initialization */
			MAX_ITERATION_NUM = this.getConf().getInt("simrank.maxiter", 10);
			
			if(getId().get() == this.getConf().getInt("simrank.src", 10)){
				double prob = 1.0 / getNumEdges();
				this.sendMessageToAllEdges(new DoubleWritable(prob));
			}
			else if(getId().get() == this.getConf().getInt("simrank.dst", 100)){
				double prob = 1.0 / getNumEdges();
				this.sendMessageToAllEdges(new DoubleWritable(-prob*1.0));
			}
		}
		else{
			double dstProb = 0.0;
			double srcProb = 0.0;

			for(DoubleWritable msg : messages){
				if(msg.get() > 0.0){
					srcProb += msg.get();
				}
				else{
					dstProb += -msg.get();
				}
			}
			
			double deltaSimRank = 1.0;
			long step = getSuperstep();
			for(int i = 1; i <= step; i++){
				deltaSimRank *= decayFactor;
			}
			
			deltaSimRank *= dstProb * srcProb;
			setValue(new DoubleWritable(this.getValue().get()+deltaSimRank));

			if(dstProb > 0)
				sendMessageToAllEdges(new DoubleWritable(-dstProb / this.getNumEdges()));
			if(srcProb > 0)
				sendMessageToAllEdges(new DoubleWritable(srcProb / this.getNumEdges()));
			aggregate("aggregate.tmppairsimrank", new DoubleWritable(deltaSimRank));
			aggregate("aggregate.pairsimrank", getValue());
		}
		voteToHalt();
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
	public static class RWApproximationVertexInputFormat extends
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

	public static class RWApproximationVertexOutputFormat extends
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
