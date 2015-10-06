package org.apache.giraph.tools.graphanalytics.peta;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.giraph.edge.Edge;
import org.apache.giraph.edge.EdgeFactory;
import org.apache.giraph.examples.Algorithm;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.io.formats.AdjacencyListTextVertexInputFormat;
import org.apache.giraph.io.formats.TextVertexOutputFormat;
import org.apache.giraph.subgraph.TripleWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.log4j.Logger;

import com.google.common.collect.Lists;

@Algorithm(
		name = "ktruss",
		description = "fake vertex program, it is used to provide the type"
)
public class KTrussVertex extends 
Vertex<IntWritable, IntWritable, NullWritable, TripleWritable>{

	@Override
	public void compute(Iterable<TripleWritable> messages) throws IOException {
		
	}
	
	/** Vertex InputFormat */
	public static class KTrussVertexInputFormat extends
		AdjacencyListTextVertexInputFormat<IntWritable, IntWritable, NullWritable> {
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
				protected IntWritable getValue(String[] values) throws IOException {
					return decodeValue(null);
				}

				@Override
				protected Iterable<Edge<IntWritable, NullWritable>> getEdges(String[] values) throws
				IOException {
					int i = 1;
					List<Edge<IntWritable, NullWritable>> edges = Lists.newLinkedList();
//					int id = Integer.valueOf(values[0]);
					while (i < values.length) {
						int target = Integer.valueOf(values[i]);
//						System.out.println("\ttarget ="+target);
//						if(id < target){
							edges.add(EdgeFactory.create(new IntWritable(target), NullWritable.get()));
//						}
						i++;
					}
					return edges;
				}

				@Override
				public IntWritable decodeId(String s) {
//					System.out.println("id="+s);
					return new IntWritable(Integer.valueOf(s));
				}

				@Override
				public IntWritable decodeValue(String s) {
					return new IntWritable(0);
				}

				@Override
				public Edge<IntWritable, NullWritable> decodeEdge(String id,
						String value) {
					return null;
				}
			}
		} 

	/**
	 * Simple VertexOutputFormat that supports {@link SimplePageRankVertex}
	 */
	public static class KTrussVertexOutputFormat extends
		TextVertexOutputFormat<IntWritable, IntWritable, NullWritable> {
			@Override
			public TextVertexWriter createVertexWriter(TaskAttemptContext context)
			throws IOException, InterruptedException {
			return new OrderedGraphWriter();
			}

			/**
			 * Simple VertexWriter that supports {@link SimplePageRankVertex}
			 */
			public class OrderedGraphWriter extends TextVertexWriter {
				@Override
				public void writeVertex(
						Vertex<IntWritable, IntWritable, NullWritable, ?> vertex)
				throws IOException, InterruptedException {
					String neighbors = "";
					for(Edge<IntWritable, NullWritable> edge : vertex.getEdges()){
						neighbors += edge.getTargetVertexId().toString() + " ";
					}
					getRecordWriter().write(
						new Text(vertex.getId().toString()),
						new Text(neighbors));
				}
		}
	}
}