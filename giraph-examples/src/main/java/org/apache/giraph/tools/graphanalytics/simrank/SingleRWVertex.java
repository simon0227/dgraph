package org.apache.giraph.tools.graphanalytics.simrank;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.giraph.aggregators.IntSumAggregator;
import org.apache.giraph.aggregators.DoubleMaxAggregator;
import org.apache.giraph.aggregators.DoubleMinAggregator;
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
		name = "A single vertex's random walk probability.",
		description = "compute the stable probability of a single vertex that reaches all other vertices after " +
				"a sufficient large steps."
)
public class SingleRWVertex 
extends Vertex<IntWritable, DoubleWritable, NullWritable, DoubleWritable>{

	private static Logger LOG = Logger.getLogger(SingleRWVertex.class);
	private static double minDelta = 1e-7;
	private int MAX_ITERATION_NUM = 50;
	private boolean isFilter = true;
	
	ArrayList<Integer> al; //count each step, how many vertices are arrived?
	
	@Override
	public void compute(Iterable<DoubleWritable> messages)
			throws IOException {		
		if(getSuperstep() > MAX_ITERATION_NUM){
			voteToHalt();
			return;
		}
		
		if(getSuperstep() == 0){
			/* initialization */
			MAX_ITERATION_NUM = this.getConf().getInt("simrank.maxiter", 50);
			isFilter = this.getConf().getBoolean("simrank.isfilter", true);
			//LOG.info("MaxIter="+MAX_ITERATION_NUM+" isFilter="+isFilter);
			if(getId().get() == this.getConf().getInt("simrank.src", 10)){
				double prob = 1.0 / getNumEdges();
				this.sendMessageToAllEdges(new DoubleWritable(prob));
			}
			setValue(new DoubleWritable(0));
		}
		else{
			double srcProb = 0.0;
			for(DoubleWritable msg : messages){
				srcProb += msg.get();
			}
			
			double curValue = getValue().get();
			
			setValue(new DoubleWritable(srcProb));

			if(!isFilter || srcProb > minDelta){
				sendMessageToAllEdges(new DoubleWritable(srcProb / this.getNumEdges()));
			}
			aggregate("aggregate.reachable.count", new IntWritable(1));
			aggregate("aggregate.max.probability", getValue());
			if(srcProb > minDelta)
				aggregate("aggregate.min.probability", getValue());
		}
		voteToHalt();
	}
	
	/** Master compute which uses aggregators. */
	public static class AggregatorsMasterCompute extends
	      DefaultMasterCompute {
	    @Override
	    public void compute() {
			int count = ((IntWritable)getAggregatedValue("aggregate.reachable.count")).get();
			double maxProb = ((DoubleWritable)getAggregatedValue("aggregate.max.probability")).get();
			double minProb = ((DoubleWritable)getAggregatedValue("aggregate.min.probability")).get();
			double avgProb = 1.0 / count;
			System.out.println("step= "+getSuperstep()+": reachable="+count+
					String.format(" maxProb=%.9f", maxProb)+
					String.format(" minProb=%.9f", minProb)+
					String.format(" avgProb=%.9f", avgProb));
	    }

	    @Override
	    public void initialize() throws InstantiationException,
	        IllegalAccessException {
	      registerAggregator("aggregate.reachable.count", IntSumAggregator.class);
	      registerAggregator("aggregate.max.probability", DoubleMaxAggregator.class);
	      registerAggregator("aggregate.min.probability", DoubleMinAggregator.class);
	    }
	  }
	  
	/** Vertex InputFormat */
	public static class SingleRWVertexInputFormat extends
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

	public static class SingleRWVertexOutputFormat extends
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
						new Text(String.format("%.9f", vertex.getValue().get())));
				}
		}
	}
}
