/**
 * Copyright 2015 José Manuel Abuín Mosquera <josemanuel.abuin@usc.es>
 * 
 * This file is part of BigBWA.
 *
 * BigBWA is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BigBWA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with BigBWA. If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.HashMap;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;



public class BigBWA extends Configured implements Tool {

	private static final Log LOG = LogFactory.getLog(BigBWA.class);

	public static void main(String[] args) throws Exception {
		// TODO Auto-generated method stub
		int res = ToolRunner.run(new Configuration(), new BigBWA(), args);
		System.exit(res);

	}

	@Override
	public int run(String[] args) throws Exception {
		Configuration conf = this.getConf();

		
		for(String argumento: args){
			LOG.info("Arg: "+argumento);
		}

		String inputPath = "";
		String outputPath = "";

		boolean useReducer = false;

		BwaOptions options = new BwaOptions(args);
		
		//We set the timeout and stablish the bwa library to call BWA methods
		conf.set("mapreduce.task.timeout", "0");
		conf.set("mapreduce.map.env", "LD_LIBRARY_PATH=./bwa.zip/");


		//==================Algorithm election==================
		//One of the algorithms is going to be in use, because tge default is always specified.
		if (options.isMemAlgorithm()) {
			//Case of the mem algorithm
			conf.set("mem", "true");
			conf.set("aln", "false");
			conf.set("bwasw","false");
		}
		
		else if (options.isAlnAlgorithm()) {
			// Case of aln algorithm
			conf.set("mem", "false");
			conf.set("aln", "true");
			conf.set("bwasw","false");
		}
		
		else if (options.isBwaswAlgorithm()) {
			// Case of bwasw algorithm
			conf.set("mem", "false");
			conf.set("aln", "false");
			conf.set("bwasw","true");
		}

		//==================Index election==================
		if(options.getIndexPath() != ""){
			conf.set("indexRoute",options.getIndexPath());
		}
		else{
			System.err.println("No index has been found. Aborting.");
			System.exit(1);
		}
		
		//==================Type of reads election==================
		//There is always going to be a type of reads, because default is paired
		if(options.isPairedReads()){
			conf.set("paired", "true");
			conf.set("single", "false");
		}
		else if(options.isSingleReads()){
			conf.set("paired", "false");
			conf.set("single", "true");
		}
		
		//==================Use of reducer==================
		if(options.isUseReducer()){
			useReducer = true;
			conf.set("useReducer", "true");
		}
		else{
			conf.set("useReducer", "false");
		}
		
		//==================Number of threads per map==================
		if (options.getNumThreads() != "0"){
			conf.set("bwathreads", options.getNumThreads());
		}
		
		//==================RG Header===================
		if (options.getReadgroupHeader() != ""){
			conf.set("rgheader", options.getReadgroupHeader());
		}
		
		
		//==================Input and output paths==================
		inputPath = options.getInputPath();
		outputPath = options.getOutputPath();

		conf.set("outputGenomics",outputPath);
		
		//==================Partition number==================
		if(options.getPartitionNumber() != 0) {
			try {
				FileSystem fs = FileSystem.get(conf);
				
				Path inputFilePath = new Path(inputPath);
				
				ContentSummary cSummary = fs.getContentSummary(inputFilePath);


				long length = cSummary.getLength();


				fs.close();
				
				conf.set("mapreduce.input.fileinputformat.split.maxsize", String.valueOf((length)/options.getPartitionNumber()));
				conf.set("mapreduce.input.fileinputformat.split.minsize", String.valueOf((length)/options.getPartitionNumber()));
			}
			catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				LOG.error(e.toString());

				System.exit(1);;
			}
			
		}
		
		
		//Job job = new Job(conf,"BigBWA_"+outputPath);
		Job job = Job.getInstance(conf,"BigBWA_"+outputPath);
		
		
		job.setJarByClass(BigBWA.class);
		job.setMapperClass(BigBWAMap.class);
		//job.setCombinerClass(BigBWACombiner.class);

		if(useReducer){
			job.setReducerClass(BigBWAReducer.class);

			job.setMapOutputKeyClass(IntWritable.class);
			job.setMapOutputValueClass(Text.class);

			job.setNumReduceTasks(1);
		}
		else{
			job.setNumReduceTasks(0);
		}

		job.setOutputKeyClass(NullWritable.class);
		job.setOutputValueClass(Text.class);



		FileInputFormat.addInputPath(job, new Path(inputPath));
		FileOutputFormat.setOutputPath(job, new Path(outputPath));

		
		return(job.waitForCompletion(true) ? 0 : 1);
	}


	//Mapper class. We follow the In-Mapper Combining pattern
	public static class BigBWAMap extends Mapper<Object,Text,IntWritable,Text> {

		File fout;
		FileOutputStream fos;
		BufferedWriter bw;
		int identificador;
		
		String tmpFileString = "";
		int jobID;

		String tmpFileString2 = "";
		File fout2;
		
		//SAI files
		String saiFile1 = "";
		String saiFile2 = "";
		
		FileOutputStream fos2;
		BufferedWriter bw2;

		String[] initValues;
		String[] values1;
		String[] values2;

		String tmpDir;
		String indexRoute;

		String rgheader = "";
		
		String outputFileName = "";
		
		//In the setup, we create each split local file
		@Override
		protected void setup(Context context) {

			identificador = context.getTaskAttemptID().getTaskID().getId();
			jobID = context.getJobID().getId();

			Configuration conf = context.getConfiguration();

			tmpDir = conf.get("hadoop.tmp.dir","/tmp/");
			indexRoute = conf.get("indexRoute");

			tmpFileString = tmpDir+"/HadoopTMPFile-"+identificador+"-"+String.valueOf(jobID);

			fout = new File(tmpFileString);
			try {
				fos = new FileOutputStream(fout);
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			bw = new BufferedWriter(new OutputStreamWriter(fos));


			if((conf.get("paired").equals("true"))){
				tmpFileString2 = tmpDir+"/HadoopTMPFile-"+identificador+"_2"+"-"+String.valueOf(jobID);
				fout2 = new File(tmpFileString2);

				try {
					fos2 = new FileOutputStream(fout2);
				} catch (FileNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

				bw2 = new BufferedWriter(new OutputStreamWriter(fos2));
			}

		} 

		//In the map method, we write the FASTQ reads to the corresponding local files
		@Override
		public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
			try{

				Configuration conf = context.getConfiguration();

				if((conf.get("paired").equals("true"))){

					initValues = value.toString().split("<part>");

					values1 = initValues[0].toString().split("<sep>");
					values2 = initValues[1].toString().split("<sep>");

					for(String newValue: values1){
						bw.write(newValue);
						bw.newLine();
					}

					for(String newValue: values2){
						bw2.write(newValue);
						bw2.newLine();
					}

					values1=null;
					values2=null;
					initValues=null;

				}
				else{
					values1 = value.toString().split("<sep>");

					for(String newValue: values1){
						bw.write(newValue);
						bw.newLine();

					}
					values1=null;
				}


			}
			catch(Exception e){
				System.out.println(e.toString());
			}
		}

		//Finally, the computation and the calling to BWA methods, it is made in the cleanup method
		@Override
		public void cleanup(Context context) throws InterruptedException{

			try {

				Configuration conf = context.getConfiguration();
				String[] args;
				

				bw.close();

				
				if(conf.get("rgheader")!=null && !conf.get("rgheader").equals("")){
					this.rgheader = conf.get("rgheader");
				}
				
				String outputDir = context.getConfiguration().get("outputGenomics");
				
				//Paired algorithms
				if((conf.get("paired").equals("true"))){
					bw2.close();

					

					if(conf.get("bwathreads")!=null && !conf.get("bwathreads").equals("")){
						
						if(this.rgheader != ""){
							
							args = new String[11];
							
							args[0] = "bwa";
							args[1] = "mem";
							args[2] = "-f";
							args[3] = tmpDir+"/Output"+this.identificador+"-"+String.valueOf(jobID)+".sam";
							args[4] = "-t";
							args[5] = conf.get("bwathreads");
							args[6] = "-R";
							args[7] = this.rgheader;
							args[8] = indexRoute;
							args[9] = tmpFileString;
							args[10] = tmpFileString2;
						}
						
						else{
							args = new String[9];

							args[0] = "bwa";
							args[1] = "mem";
							args[2] = "-f";
							args[3] = tmpDir+"/Output"+this.identificador+"-"+String.valueOf(jobID)+".sam";
							args[4] = "-t";
							args[5] = conf.get("bwathreads");
							args[6] = indexRoute;
							args[7] = tmpFileString;
							args[8] = tmpFileString2;
						}
						
						outputFileName = args[3];

						//bwa execution
						BwaJni.Bwa_Jni(args);
					}
					else if((conf.get("mem")!=null)&&(conf.get("mem").equals("true"))){
						
						if(this.rgheader != ""){
							args = new String[9];

							args[0] = "bwa";
							args[1] = "mem";
							args[2] = "-f";
							args[3] = tmpDir+"/Output"+this.identificador+"-"+String.valueOf(jobID)+".sam";
							args[4] = "-R";
							args[5] = this.rgheader;
							args[6] = indexRoute;
							args[7] = tmpFileString;
							args[8] = tmpFileString2;
						}
						
						else{
							args = new String[7];

							args[0] = "bwa";
							args[1] = "mem";
							args[2] = "-f";
							args[3] = tmpDir+"/Output"+this.identificador+"-"+String.valueOf(jobID)+".sam";
							args[4] = indexRoute;
							args[5] = tmpFileString;
							args[6] = tmpFileString2;

							outputFileName = args[3];
						}
						
						

						//bwa execution
						BwaJni.Bwa_Jni(args);
					}
					else if((conf.get("bwasw")!=null)&&(conf.get("bwasw").equals("true"))){
						args = new String[7];

						args[0] = "bwa";
						args[1] = "bwasw";
						args[2] = "-f";
						args[3] = tmpDir+"/Output"+this.identificador+"-"+String.valueOf(jobID)+".sam";
						args[4] = indexRoute;
						args[5] = tmpFileString;
						args[6] = tmpFileString2;

						outputFileName = args[3];

						//bwa execution
						BwaJni.Bwa_Jni(args);
					}
					else if((conf.get("aln")!=null)&&(conf.get("aln").equals("true"))){
						args = new String[6];

						this.saiFile1 = tmpDir+"/Output"+this.identificador+"-"+String.valueOf(jobID)+".sai";
						this.saiFile2 = tmpDir+"/Output"+this.identificador+"-2-"+String.valueOf(jobID)+".sai";

						args[0] = "bwa";
						args[1] = "aln";
						args[2] = "-f";
						args[3] = saiFile1;
						args[4] = indexRoute;
						args[5] = tmpFileString;

						//bwa execution for aln1
						BwaJni.Bwa_Jni(args);

						LOG.info("ALN - End of first alignment");
						String[] args2 = new String[6];

						args2[0] = "bwa";
						args2[1] = "aln";
						args2[2] = "-f";
						args2[3] = saiFile2;
						args2[4] = indexRoute;
						args2[5] = tmpFileString2;

						LOG.info("ALN - Begin of second alignment");
						for(String newArg: args2){
							LOG.warn("Arg: "+newArg);
						}

						//bwa execution for aln2
						BwaJni.Bwa_Jni(args2);

						//Sampe
						if(this.rgheader!=""){
							args = new String[11];
							args[0] = "bwa";
							args[1] = "sampe";
							args[2] = "-f";
							args[3] = tmpDir+"/Output"+this.identificador+"-"+String.valueOf(jobID)+".sam";
							args[4] = indexRoute;
							args[5] = "-r";
							args[6] = this.rgheader;
							args[7] = saiFile1;
							args[8] = saiFile2;
							args[9] = tmpFileString;
							args[10] = tmpFileString2;
						}
						else{
							args = new String[9];
							args[0] = "bwa";
							args[1] = "sampe";
							args[2] = "-f";
							args[3] = tmpDir+"/Output"+this.identificador+"-"+String.valueOf(jobID)+".sam";
							args[4] = indexRoute;
							args[5] = saiFile1;
							args[6] = saiFile2;
							args[7] = tmpFileString;
							args[8] = tmpFileString2;
						}
						
						

						outputFileName = args[3];
						
						//bwa execution of sampe
						LOG.info("ALN - Begin of sampe");
						BwaJni.Bwa_Jni(args);

						LOG.info("ALN - End of sampe");

						File tempFile = new File(saiFile1);
						tempFile.delete();

						tempFile = new File(saiFile2);
						tempFile.delete();

						

					}

					//We copy the results to HDFS and delete tmp files from local filesystem
					FileSystem fs = FileSystem.get(context.getConfiguration());

					fs.copyFromLocalFile(new Path(outputFileName), new Path(outputDir+"/Output"+this.identificador+".sam"));
					fs.copyFromLocalFile(new Path(tmpFileString), new Path(outputDir+"/Input"+this.identificador+"_1.fq"));
					fs.copyFromLocalFile(new Path(tmpFileString2), new Path(outputDir+"/Input"+this.identificador+"_2.fq"));

					File outputFile = new File(outputFileName);
					outputFile.delete();

					fout.delete();
					fout2.delete();

					if((conf.get("useReducer")!=null)&&(conf.get("useReducer").equals("true"))){
						context.write(new IntWritable(this.identificador), new Text(outputDir+"/Output"+this.identificador+".sam"));
					}


				}
				//Single algorithms
				else{
					if(conf.get("mem").equals("true")){
						//String outputDir = context.getConfiguration().get("outputGenomics");
						args = new String[6];

						args[0] = "bwa";
						args[1] = "mem";
						args[2] = "-f";
						args[3] = tmpDir+"/Output"+this.identificador+"-"+String.valueOf(jobID)+".sam";
						args[4] = indexRoute;
						args[5] = tmpFileString;

						//bwa execution
						BwaJni.Bwa_Jni(args);

						//We copy the results to HDFS and delete tmp files from local filesystem
						FileSystem fs = FileSystem.get(context.getConfiguration());

						fs.copyFromLocalFile(new Path(args[3]), new Path(outputDir+"/Output"+this.identificador+".sam"));
						fs.copyFromLocalFile(new Path(tmpFileString), new Path(outputDir+"/Input"+this.identificador+".fq"));

						File outputFile = new File(args[3]);
						outputFile.delete();
						fout.delete();

					}
					else if(conf.get("bwasw").equals("true")){
						//String outputDir = context.getConfiguration().get("outputGenomics");
						args = new String[6];

						args[0] = "bwa";
						args[1] = "bwasw";
						args[2] = "-f";
						args[3] = tmpDir+"/Output"+this.identificador+"-"+String.valueOf(jobID)+".sam";
						args[4] = indexRoute;
						args[5] = tmpFileString;

						this.outputFileName = args[3];
						
						//bwa execution
						BwaJni.Bwa_Jni(args);

						//We copy the results to HDFS and delete tmp files from local filesystem
						FileSystem fs = FileSystem.get(context.getConfiguration());

						fs.copyFromLocalFile(new Path(args[3]), new Path(outputDir+"/Output"+this.identificador+".sam"));
						fs.copyFromLocalFile(new Path(tmpFileString), new Path(outputDir+"/Input"+this.identificador+".fq"));

						File outputFile = new File(args[3]);
						outputFile.delete();
						fout.delete();

					}
					else if(conf.get("aln").equals("true")){
						args = new String[6];
						
						String saiFile = tmpDir+"/Output"+this.identificador+"-"+String.valueOf(jobID)+".sai";

						args[0] = "bwa";
						args[1] = "aln";
						args[2] = "-f";
						args[3] = saiFile;
						args[4] = indexRoute;
						args[5] = tmpFileString;

						//bwa execution
						BwaJni.Bwa_Jni(args);

						args = new String[7];
						args[0] = "bwa";
						args[1] = "samse";
						args[2] = "-f";
						args[3] = tmpDir+"/Output"+this.identificador+"-"+String.valueOf(jobID)+".sam";
						args[4] = indexRoute;
						args[5] = saiFile;
						args[6] = tmpFileString;

						this.outputFileName = args[3];
						
						//bwa execution of sampe
						BwaJni.Bwa_Jni(args);

						File tempFile = new File(saiFile);
						tempFile.delete();

						
						
						//We copy the results to HDFS and delete tmp files from local filesystem
						//String outputDir = context.getConfiguration().get("outputGenomics");

						FileSystem fs = FileSystem.get(context.getConfiguration());

						fs.copyFromLocalFile(new Path(args[3]), new Path(outputDir+"/Output"+this.identificador+".sai"));
						fs.copyFromLocalFile(new Path(tmpFileString), new Path(outputDir+"/Input"+this.identificador+".fq"));

						File outputFile = new File(args[3]);

						fout.delete();
						outputFile.delete();
					}
					
					if((conf.get("useReducer")!=null)&&(conf.get("useReducer").equals("true"))){
						context.write(new IntWritable(this.identificador), new Text(outputDir+"/Output"+this.identificador+".sam"));
					}

				}

			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				
				//Clean temporary files
				
				//FASTQ splits
				this.fout.delete();
				this.fout2.delete();
				
				//SAI outputs
				if(!this.saiFile1.isEmpty()){
					File tempFile = new File(this.saiFile1);
					tempFile.delete();
				}
				
				if(!this.saiFile2.isEmpty()){
					File tempFile = new File(this.saiFile2);
					tempFile.delete();
				}

				//SAM Output
				if(!this.outputFileName.isEmpty()){
					File tempFile = new File(this.outputFileName);
					tempFile.delete();
				}
				

				
			}

		}


	}

	public static class BigBWAReducer extends Reducer<IntWritable,Text,NullWritable,Text> {

		//private String outputFile;
		//private String outputDir;
		private HashMap<Integer,String> inputFiles;

		@Override
		protected void setup(Context context) {

			//this.outputDir = context.getConfiguration().get("outputGenomics");
			//this.outputFile = this.outputDir+"/FinalOutput.sam";

			this.inputFiles = new HashMap<Integer,String>();

		}

		@Override
		public void reduce(IntWritable key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
			try{

				//In theory, there is just one value per key
				for (Text val : values) {
					inputFiles.put(key.get(), val.toString());
				}

			}
			catch(Exception e){
				System.out.println(e.toString());
			}
		}

		@Override
		public void cleanup(Context context) throws IOException, InterruptedException {

			FileSystem fs = FileSystem.get(context.getConfiguration());

			int fileNumber = this.inputFiles.size();

			boolean readHeader = true;

			for(int i = 0; i< fileNumber; i++){

				String currentFile = this.inputFiles.get(i);

				BufferedReader d = new BufferedReader(new InputStreamReader(fs.open(new Path(currentFile))));

				String line = "";

				while ((line = d.readLine())!=null) {

					if((line.startsWith("@") && readHeader) || (!line.startsWith("@")) ){
						//bufferOut.write(line);
						context.write(NullWritable.get(), new Text(line));
					}


				}

				readHeader = false;

				d.close();

				fs.delete(new Path(currentFile), true);

			}

			//bufferOut.close();

		}

	}

}

