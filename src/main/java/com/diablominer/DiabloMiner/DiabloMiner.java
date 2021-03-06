/*
 *  DiabloMiner - OpenCL miner for BitCoin
 *  Copyright (C) 2010, 2011 Patrick McFarland <diablod3@gmail.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.diablominer.DiabloMiner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Formatter;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.codec.binary.Base64;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.NullNode;
import org.codehaus.jackson.node.ObjectNode;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CLCommandQueue;
import org.lwjgl.opencl.CLContext;
import org.lwjgl.opencl.CLContextCallback;
import org.lwjgl.opencl.CLDevice;
import org.lwjgl.opencl.CLKernel;
import org.lwjgl.opencl.CLMem;
import org.lwjgl.opencl.CLPlatform;
import org.lwjgl.opencl.CLProgram;

class DiabloMiner {
  final static int EXECUTION_TOTAL = 3;
  final static long TIME_OFFSET = 7500;
  final static int OUTPUTS = 16;
  final static long TWO32 = 4294967295L;
  final static byte[] EMPTY_BUFFER = new byte[4 * OUTPUTS];

  URL bitcoind;
  URL bitcoindLongPoll;
  Proxy proxy = null;
  String userPass;
  int getWorkRefresh = 5000;

  boolean hwcheck = true;
  boolean debug = false;
  boolean edebug = false;

  double targetFPS = 30.0;
  double targetFPSBasis;
  long maxWorkSize;

  int forceWorkSize = 0;
  int zloops = 1;
  int vectors = 1;
  int vectorWidth;
  boolean xvectors = false;
  boolean yvectors = false;
  boolean zvectors = false;

  String source;

  boolean running = true;
  Thread mainThread;

  List<DeviceState> deviceStates = new ArrayList<DeviceState>();
  int deviceStatesCount;

  long startTime;

  AtomicLong hashCount = new AtomicLong(0);

  AtomicLong currentBlocks = new AtomicLong(0);
  AtomicLong currentAttempts = new AtomicLong(0);
  AtomicLong currentRejects = new AtomicLong(0);
  AtomicLong currentHWErrors = new AtomicLong(0);
  Set<String> enabledDevices = null;

  final static String UPPER[] = { "X", "Y", "Z", "W", "T", "A", "B", "C" };
  final static String LOWER[] = { "x", "y", "z", "w", "t", "a", "b", "c" };
  final static String CLEAR = "                                                                             ";

  public static void main(String [] args) throws Exception {
    DiabloMiner diabloMiner = new DiabloMiner();

    diabloMiner.execute(args);
  }

  void execute(String[] args) throws Exception {
    mainThread = Thread.currentThread();

    String user = "diablo";
    String pass = "miner";
    String ip = "127.0.0.1";
    String port = "8332";
    String path = "";
    String url = "url";

    Options options = new Options();
    options.addOption("u", "user", true, "bitcoin host username");
    options.addOption("p", "pass", true, "bitcoin host password");
    options.addOption("f", "fps", true, "target execution timing");
    options.addOption("w", "worksize", true, "override worksize");
    options.addOption("o", "host", true, "bitcoin host IP");
    options.addOption("r", "port", true, "bitcoin host port");
    options.addOption("g", "getWork", true, "seconds between getWork refresh");
    options.addOption("D", "devices", true, "devices to enable");
    options.addOption("x", "proxy", true, "optional proxy settings IP:PORT<:username:password>");
    options.addOption("l", "url", true, "bitcoin host url");
    options.addOption("z", "loops", true, "kernel loops (PoT exp, 0 is off)");
    options.addOption("v", "vectors", true, "vector size in kernel");
    options.addOption("d", "debug", false, "enable debug output");
    options.addOption("dd", "edebug", false, "enable extra debug output");
    options.addOption("ds", "ksource", false, "output kernel source and quit");
    options.addOption("h", "help", false, "this help");

    PosixParser parser = new PosixParser();

    CommandLine line = null;

    try {
      line = parser.parse(options, args);

      if(line.hasOption("help")) {
        throw new ParseException("A wise man once said, '↑ ↑ ↓ ↓ ← → ← → B A'");
      }
    } catch (ParseException e) {
      System.out.println(e.getLocalizedMessage() + "\n");
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("DiabloMiner -u myuser -p mypassword [args]\n", "", options,
          "\nRemember to set rpcuser and rpcpassword in your ~/.bitcoin/bitcoin.conf " +
          "before starting bitcoind or bitcoin --daemon");
      System.exit(0);
    }

    if(line.hasOption("user"))
      user = line.getOptionValue("user");

    if(line.hasOption("pass"))
      pass = line.getOptionValue("pass");

    if(line.hasOption("fps"))
      targetFPS = Float.parseFloat(line.getOptionValue("fps"));

    if(line.hasOption("worksize"))
      forceWorkSize = Integer.parseInt(line.getOptionValue("worksize"));

    if(line.hasOption("getWork"))
      getWorkRefresh = Integer.parseInt(line.getOptionValue("getWork")) * 1000;

    if(line.hasOption("debug"))
      debug = true;

    if(line.hasOption("edebug")) {
      debug = true;
      edebug = true;
    }

    if(line.hasOption("host"))
      ip = line.getOptionValue("host");

    if(line.hasOption("port"))
      port = line.getOptionValue("port");

    if(line.hasOption("loops"))
      zloops = (int) Math.pow(2, Integer.parseInt(line.getOptionValue("loops")));

    if(line.hasOption("vectors")) {
      vectors = Integer.parseInt(line.getOptionValue("vectors"));

      if(!((vectors >= 1 && vectors <= 6) ||
           (vectors >= 17 && vectors <= 24) ||
           (vectors >= 33 && vectors <= 44)))
        throw new ParseException("Only 1 through 6, 17 through 24, 33 through 44 are valid for vectors");

      if(vectors == 2  || vectors == 3  || vectors == 4  || vectors == 5  || vectors == 6 ||
         vectors >= 36)
        xvectors = true;

      if(vectors == 4  || vectors == 5  || vectors == 6 ||
         vectors >= 40)
        yvectors = true;

      if(vectors == 6 ||
         vectors >= 44)
        zvectors = true;

      if(vectors > 32) {
        vectors -= 32;
        vectorWidth = 4;
      } else if(vectors > 16) {
        vectors -= 16;
        vectorWidth = 1;
      } else {
        vectorWidth = 2;
      }
    }

    if(line.hasOption("devices")){
      String devices[] = line.getOptionValue("devices").split(",");
      enabledDevices = new HashSet<String>();
      for(String s : devices)
        enabledDevices.add(s);
    }

    if(line.hasOption("proxy")) {
    	final String[] proxySettings = line.getOptionValue("proxy").split(":");

      if(proxySettings.length >= 2) {
        proxy = new Proxy(Type.HTTP, new InetSocketAddress(proxySettings[0], Integer.valueOf(proxySettings[1])));
      }

      if(proxySettings.length >= 3) {
        Authenticator.setDefault(new Authenticator() {
          protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(proxySettings[2], proxySettings[3].toCharArray());
          }
        });
      }
    }

    if(line.hasOption("url")) {
      url = line.getOptionValue("url").replace("\\@", "+++++");

      String protocol = "http";

      String[] split = url.split("://");

      if(split.length > 1)
        protocol = split[0];

      split = split[split.length - 1].split("/", 2);

      if(split.length > 1)
        path = split[1];

      split = split[0].split("@");

      String[] split2;

      if(split.length > 1) {
        split2 = split[0].split(":");

        if(split2[0].length() > 0) {
          user = split2[0];

          if(split2.length > 1 && split2[1].length() > 0)
            pass = split2[1];
        }
      }

      split2 = split[split.length - 1].split(":");

      if(split2[0].length() > 0) {
        ip = split2[0];

        if(split2.length > 1 && split2[1].length() > 0)
          port = split2[1];
      }

      url = protocol + "://" + ip + ":" + port + "/" + path;
    } else {
      url = "http://"+ ip + ":" + port + "/";
    }

    user = user.replace("+++++", "@");

    bitcoind = new URL(url);
    userPass = "Basic " + Base64.encodeBase64String((user + ":" + pass).getBytes()).trim().replace("\r\n", "");

    InputStream stream = DiabloMiner.class.getResourceAsStream("/DiabloMiner.cl");
    byte[] data = new byte[64 * 1024];
    stream.read(data);
    source = new String(data).trim();
    stream.close();

    String sourceLines[] = source.split("\n");
    source = "";
    long vectorOffset = (TWO32 / vectors);
    long vectorBase = 0;
    long actualVectors = vectors;

    if(xvectors)
      actualVectors -= vectorWidth - 1;

    if(yvectors)
      actualVectors -= vectorWidth - 1;

    if(zvectors)
      actualVectors -= vectorWidth - 1;

    for(int x = 0; x < sourceLines.length; x++) {
      String sourceLine = sourceLines[x];

      if((sourceLine.contains("Z") || sourceLine.contains("z")) && !sourceLine.contains("__attribute__")) {
        for(int y = 0; y < actualVectors; y++) {
          String replace = sourceLine;

          if((y == 0 && xvectors == true) ||
             (y == 1 && yvectors == true) ||
             (y == 2 && zvectors == true)) {
            if(replace.contains("typedef")) {
              if(vectorWidth == 2)
                replace = replace.replace("uint", "uint2");
              else if(vectorWidth == 4)
                replace = replace.replace("uint", "uint4");
            } else if(replace.contains("global")) {
              if(vectorWidth == 2) {
                replace = replace.replace(";", " + (uint2)(" + vectorBase + ", " + (vectorBase + vectorOffset) + ");");
                vectorBase += vectorOffset * 2;
              } else if(vectorWidth == 4) {
                replace = replace.replace(";", " + (uint4)(" + vectorBase + ", " + (vectorBase + vectorOffset) +  ", " + (vectorBase + vectorOffset * 2) +  ", " + (vectorBase + vectorOffset * 3) + ");");
                vectorBase += vectorOffset * 4;
              }
            } else if(sourceLine.contains("0x136032ED")) {
              if(vectorWidth ==  2) {
                replace = replace.replace("ZH", "ZH.x").replaceAll("nonce", "nonce.x")
                        + replace.replace("ZH", "ZH.y").replaceAll("nonce", "nonce.y");
              } else if(vectorWidth == 4) {
                replace = replace.replace("ZH", "ZH.s0").replaceAll("nonce", "nonce.s0")
                        + replace.replace("ZH", "ZH.s1").replaceAll("nonce", "nonce.s1")
                        + replace.replace("ZH", "ZH.s2").replaceAll("nonce", "nonce.s2")
                        + replace.replace("ZH", "ZH.s3").replaceAll("nonce", "nonce.s3");
              }
            }
          } else {
            if(replace.contains("global")) {
              replace = replace.replace(";", " + " + vectorBase + ";");
              vectorBase += vectorOffset;
            }
          }

          source += replace.replaceAll("Z", UPPER[y]).replaceAll("z", LOWER[y]) + "\n";
        }
      } else
        source += sourceLine + "\n";
    }

    if(line.hasOption("ds")) {
      System.out.println("\n---\n" + source);
      System.exit(0);
    }

    targetFPSBasis = 1000.0 / (targetFPS * EXECUTION_TOTAL);
    maxWorkSize = TWO32 / zloops / vectors;

    info("Started");
    info("Connecting to: " + url);

    CL.create();

    List<CLPlatform> platforms = CLPlatform.getPlatforms();

    if(platforms == null) {
      error("No OpenCL platforms found");
      System.exit(0);
    }

    int count = 1;
    int platformCount = 0;

    for(CLPlatform platform : platforms) {
      info("Using " + platform.getInfoString(CL10.CL_PLATFORM_NAME).trim() + " " +
            platform.getInfoString(CL10.CL_PLATFORM_VERSION));

      List<CLDevice> devices = platform.getDevices(CL10.CL_DEVICE_TYPE_GPU | CL10.CL_DEVICE_TYPE_ACCELERATOR);

      if(devices == null) {
        error("OpenCL platform " + platform.getInfoString(CL10.CL_PLATFORM_NAME).trim() + " contains no devices");
        System.exit(0);
      }

      for (CLDevice device : devices) {
        if(enabledDevices == null || enabledDevices.contains(platformCount + "." + count) || enabledDevices.contains(Integer.toString(count)))
          deviceStates.add(this.new DeviceState(platform, device, count));
        count++;
      }
      platformCount++;
    }

    CL10.clUnloadCompiler();

    deviceStatesCount = deviceStates.size();

    long previousHashCount = 0;
    long previousAdjustedHashCount = 0;
    long previousAdjustedStartTime = startTime = (getNow()) - 1;
    StringBuilder hashMeter = new StringBuilder(80);
    Formatter hashMeterFormatter = new Formatter(hashMeter);

    while(running) {
      for(int i = 0; i < deviceStatesCount; i++)
        deviceStates.get(i).checkDevice();

      long now = getNow();
      long currentHashCount = hashCount.get();
      long adjustedHashCount = (currentHashCount - previousHashCount) / (now - previousAdjustedStartTime);
      double hashLongCount = currentHashCount / (now - startTime) / 1000.0;

      if(now - startTime > TIME_OFFSET * 2) {
        double averageHashCount = (adjustedHashCount + previousAdjustedHashCount) / 2 / 1000.0;

        hashMeter.setLength(0);

        if(!debug) {
          hashMeterFormatter.format("\rmhash %.1f/%.1f | accept: %d | reject: %d | hw error: %d",
                averageHashCount, hashLongCount, currentBlocks.get(), currentRejects.get(), currentHWErrors.get());
        } else {
          hashMeterFormatter.format("\rmhash %.1f/%.1f | a/r/hwe: %d/%d/%d | ghash: ",
                averageHashCount, hashLongCount, currentBlocks.get(), currentRejects.get(), currentHWErrors.get());

          double basisAverage = 0.0;

          for(int i = 0; i < deviceStates.size(); i++) {
            DeviceState deviceState = deviceStates.get(i);

            hashMeterFormatter.format("%.1f ", deviceState.deviceHashCount.get() / 1000.0 / 1000.0 / 1000.0);
            basisAverage += deviceState.basis;
          }

          basisAverage = 1000 / (basisAverage / deviceStates.size() * EXECUTION_TOTAL);

          hashMeterFormatter.format("| fps: %.1f", basisAverage);
        }

        System.out.print(hashMeter);
      } else {
        System.out.print("\rWaiting...");
      }

      if(getNow() - TIME_OFFSET * 2 > previousAdjustedStartTime) {
        previousHashCount = currentHashCount;
        previousAdjustedHashCount = adjustedHashCount;
        previousAdjustedStartTime = now - 1;
      }

      try {
        if(now - startTime > TIME_OFFSET)
          Thread.sleep(1000);
        else
          Thread.sleep(1);
      } catch (InterruptedException e) { }
    }
  }

  static int rot(int x, int y) {
    return (x >>> y) | (x << (32 - y));
  }

  static void sharound(int out[], int na, int nb, int nc, int nd, int ne, int nf, int ng, int nh, int x, int K) {
    int a = out[na];
    int b = out[nb];
    int c = out[nc];
    int d = out[nd];
    int e = out[ne];
    int f = out[nf];
    int g = out[ng];
    int h = out[nh];

    int t1 = h + (rot(e, 6) ^ rot(e, 11) ^ rot(e, 25)) + ((e & f) ^ ((~e) & g)) + K + x;
    int t2 = (rot(a, 2) ^ rot(a, 13) ^ rot(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));

    out[nd] = d + t1;
    out[nh] = t1 + t2;
  }

  static String getDateTime() {
    return "[" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date()) + "]";
  }

  void info(String msg) {
    System.out.println("\r" + CLEAR + "\r" + getDateTime() + " " + msg);
    mainThread.interrupt();
  }

  void debug(String msg) {
    if(debug) {
      System.out.println("\r" + CLEAR + "\r" + getDateTime() + " DEBUG: " + msg);
      mainThread.interrupt();
    }
  }

  void edebug(String msg) {
    if(edebug) {
      System.out.println("\r" + CLEAR + "\r" + getDateTime() + " DEBUG: " + msg);
      mainThread.interrupt();
    }
  }

  void error(String msg) {
    System.err.println("\r" + CLEAR + "\r" + getDateTime() + " ERROR: " + msg);
    mainThread.interrupt();
  }

  long getNow() {
    return System.nanoTime() / 1000000;
  }

  class DeviceState {
    final String deviceName;

    final CLDevice device;
    final CLContext context;

    CLProgram program;
    final CLKernel kernel;

    long workSize;
    long workSizeBase;
    double basis;

    final PointerBuffer localWorkSize = BufferUtils.createPointerBuffer(1);

    final ExecutionState executions[] = new ExecutionState[EXECUTION_TOTAL];

    AtomicLong deviceHashCount = new AtomicLong(0);

    AtomicLong runs = new AtomicLong(0);
    long lastRuns = 0;
    long lastTime = startTime;

    boolean hasBitAlign = false;
    int loops = 1;

    DeviceState(CLPlatform platform, CLDevice device, int count) throws Exception {
      this.device = device;

      PointerBuffer properties = BufferUtils.createPointerBuffer(3);
      properties.put(CL10.CL_CONTEXT_PLATFORM).put(platform.getPointer()).put(0).flip();
      int err = 0;

      deviceName = device.getInfoString(CL10.CL_DEVICE_NAME).trim() + " (#" + count + ")";
      int deviceCU = device.getInfoInt(CL10.CL_DEVICE_MAX_COMPUTE_UNITS);
      long deviceWorkSize = device.getInfoSize(CL10.CL_DEVICE_MAX_WORK_GROUP_SIZE);

      context = CL10.clCreateContext(properties, device, new CLContextCallback() {
        protected void handleMessage(String errinfo, ByteBuffer private_info) {
          error(errinfo);
        }
      }, null);

      ByteBuffer extb = BufferUtils.createByteBuffer(1024);
      CL10.clGetDeviceInfo(device, CL10.CL_DEVICE_EXTENSIONS, extb, null);
      byte[] exta = new byte[1024];
      extb.get(exta);

      if(new String(exta).contains("cl_amd_media_ops"))
        hasBitAlign = true;

      if(zloops > 1)
        loops = zloops;
      else if(zloops <= 1)
        loops = 1;

      String compileOptions = "";

      if(forceWorkSize > 0)
        compileOptions = " -D WORKSIZE=" + forceWorkSize;
      else
        compileOptions = " -D WORKSIZE=" + deviceWorkSize;

      if(hasBitAlign)
        compileOptions += " -D BITALIGN";

      if(loops > 1) {
        compileOptions += " -D DOLOOPS";
        compileOptions += " -D LOOPS=" + loops;
      }

      program = CL10.clCreateProgramWithSource(context, source, null);

      err = CL10.clBuildProgram(program, device, compileOptions, null);
      if(err != CL10.CL_SUCCESS) {
        ByteBuffer logBuffer = BufferUtils.createByteBuffer(1024);
        byte[] log = new byte[1024];

        CL10.clGetProgramBuildInfo(program, device, CL10.CL_PROGRAM_BUILD_LOG, logBuffer, null);

        logBuffer.get(log);

        System.out.println(new String(log));

        error("Failed to build program on " + deviceName);
        System.exit(0);
      }

      if(hasBitAlign) {
        info("BFI_INT patching enabled, disabling hardware checking");
        hwcheck = false;

        int binarySize = (int)program.getInfoSizeArray(CL10.CL_PROGRAM_BINARY_SIZES)[0];

        ByteBuffer binary = BufferUtils.createByteBuffer(binarySize);
        program.getInfoBinaries(binary);

        for(int pos = 0; pos < binarySize - 4; pos++) {
          if((long)(0xFFFFFFFF & binary.getInt(pos)) == 0x464C457FL &&
             (long)(0xFFFFFFFF & binary.getInt(pos + 4)) == 0x64010101L) {
            boolean firstText = true;

            int offset = binary.getInt(pos + 32);
            short entrySize = binary.getShort(pos + 46);
            short entryCount = binary.getShort(pos + 48);
            short index = binary.getShort(pos + 50);

            int header = pos + offset;

            int nameTableOffset = binary.getInt(header + index * entrySize + 16);
            int size = binary.getInt(header + index * entrySize + 20);

            int entry = header;

            for(int section = 0; section < entryCount; section++) {
              int nameIndex = binary.getInt(entry);
              offset = binary.getInt(entry + 16);
              size = binary.getInt(entry + 20);

              int name = pos + nameTableOffset + nameIndex;

              if((long)(0xFFFFFFFF & binary.getInt(name)) == 0x7865742E) {
                if(firstText) {
                  firstText = false;
                } else {
                  int sectionStart = pos + offset;
                  for(int i = 0; i < size / 8; i++) {
                    long instruction1 = (long)(0xFFFFFFFF & binary.getInt(sectionStart + i * 8));
                    long instruction2 = (long)(0xFFFFFFFF & binary.getInt(sectionStart + i * 8 + 4));

                    if((instruction1 & 0x02001000L) == 0x00000000L &&
                       (instruction2 & 0x9003F000L) == 0x0001A000L) {
                      instruction2 ^= (0x0001A000L ^ 0x0000C000L);

                      binary.putInt(sectionStart + i * 8 + 4, (int)instruction2);
                    }
                  }
                }
              }

              entry += entrySize;
            }

            break;
          }
        }

        IntBuffer binaryErr = BufferUtils.createIntBuffer(1);

        CL10.clReleaseProgram(program);
        program = CL10.clCreateProgramWithBinary(context, device, binary, binaryErr, null);

        err = CL10.clBuildProgram(program, device, compileOptions, null);

        if(err != CL10.CL_SUCCESS) {
          error("Failed to BFI_INT patch kernel on " + deviceName);
          System.exit(0);
        }
      }

      kernel = CL10.clCreateKernel(program, "search", null);
      if(kernel == null) {
        error("Failed to create kernel on " + deviceName);
        System.exit(0);
      }

      if(forceWorkSize == 0) {
        ByteBuffer rkwgs = BufferUtils.createByteBuffer(8);

        err = CL10.clGetKernelWorkGroupInfo(kernel, device, CL10.CL_KERNEL_WORK_GROUP_SIZE, rkwgs, null);

        localWorkSize.put(0, rkwgs.getLong(0));

        if(!(err == CL10.CL_SUCCESS) || localWorkSize.get(0) == 0)
          localWorkSize.put(0, deviceWorkSize);
      } else {
        localWorkSize.put(0, forceWorkSize);
      }

      info("Added " + deviceName + " (" + deviceCU + " CU, local work size of " + localWorkSize.get(0) + ")");

      workSizeBase = localWorkSize.get(0) * localWorkSize.get(0);

      workSize = workSizeBase * 32;

      for(int i = 0; i < EXECUTION_TOTAL; i++) {
        executions[i] = this.new ExecutionState();
        new Thread(executions[i], "DiabloMiner Executor (" + deviceName + "/" + i + ")").start();
      }
    }

    void checkDevice() {
      long now = getNow();
      long elapsed = now - lastTime;
      long currentRuns = runs.get();

      if(now > startTime + TIME_OFFSET * 2 && currentRuns > lastRuns + targetFPS) {
        basis = (double)elapsed / (double)(currentRuns - lastRuns);

        if(basis < targetFPSBasis / 3)
          workSize += workSizeBase * 30;
        else if(basis < targetFPSBasis / 1.5)
          workSize += workSizeBase * 15;
        else if(basis < targetFPSBasis)
          workSize += workSizeBase;
        else if(basis > targetFPSBasis * 1.5)
          workSize -= workSizeBase * 15;
        else if(basis > targetFPSBasis)
          workSize -= workSizeBase;

        if(workSize < workSizeBase)
          workSize = workSizeBase;
        else if(workSize > maxWorkSize)
          workSize = maxWorkSize;

        lastRuns = currentRuns;
        lastTime = now;
      }
    }

    class ExecutionState implements Runnable {
      final CLCommandQueue queue;
      ByteBuffer buffer[] = new ByteBuffer[2];
      final CLMem output[] = new CLMem[2];
      int bufferIndex = 0;

      boolean longPoll = false;

      final int[] midstate2 = new int[16];

      final MessageDigest digestInside = MessageDigest.getInstance("SHA-256");
      final MessageDigest digestOutside = MessageDigest.getInstance("SHA-256");
      final ByteBuffer digestInput = ByteBuffer.allocate(80);
      byte[] digestOutput;

      final GetWorkParser currentWork = this.new GetWorkParser();;

      final PointerBuffer workSizeTemp = BufferUtils.createPointerBuffer(1);

      final IntBuffer errBuf = BufferUtils.createIntBuffer(1);
      int err;

      ExecutionState() throws NoSuchAlgorithmException {
        queue = CL10.clCreateCommandQueue(context, device, 0, errBuf);

        if(queue == null || errBuf.get(0) != CL10.CL_SUCCESS) {
          error("Failed to allocate queue");
          System.exit(0);
        }

        buffer[0] = BufferUtils.createByteBuffer(4 * OUTPUTS);
        buffer[1] = BufferUtils.createByteBuffer(4 * OUTPUTS);

        for(int i = 0; i < 2; i++) {
          output[i] = CL10.clCreateBuffer(context, CL10.CL_MEM_WRITE_ONLY, 4 * OUTPUTS, errBuf);
          if(output == null || errBuf.get(0) != CL10.CL_SUCCESS) {
            error("Failed to allocate output buffer");
            System.exit(0);
          }

          buffer[i].put(EMPTY_BUFFER, 0, 4 * OUTPUTS);
          buffer[i].position(0);
          CL10.clEnqueueWriteBuffer(queue, output[i], CL10.CL_FALSE, 0, buffer[i], null, null);
        }
      }

      public void run() {
        boolean submittedBlock;
        boolean resetBuffer;
        boolean skip = false;

        while(running) {
          submittedBlock = false;
          resetBuffer = false;

          if(skip == false) {
            for(int z = 0; z < OUTPUTS; z++) {
              int nonce = buffer[bufferIndex].getInt(z * 4);

              if(nonce != 0) {
                for(int j = 0; j < 19; j++)
                  digestInput.putInt(j*4, currentWork.data[j]);

                digestInput.putInt(19*4, nonce);

                digestOutput = digestOutside.digest(digestInside.digest(digestInput.array()));

                long G =
                  ((long)(0xFF & digestOutput[27]) << 24) |
                  ((long)(0xFF & digestOutput[26]) << 16) |
                  ((long)(0xFF & digestOutput[25]) << 8) |
                  ((long)(0xFF & digestOutput[24]));

                long H =
                  ((long)(0xFF & digestOutput[31]) << 24) |
                  ((long)(0xFF & digestOutput[30]) << 16) |
                  ((long)(0xFF & digestOutput[29]) << 8)  |
                  ((long)(0xFF & digestOutput[28]));

                edebug("Attempt " + currentAttempts.incrementAndGet() + " found on " + deviceName);

                if(G <= currentWork.target[6]) {
                  if(H == 0) {
                    currentWork.sendWork(nonce);
                    submittedBlock = true;
                  } else {
                    if(hwcheck)
                      error("Invalid solution " + currentHWErrors.incrementAndGet() + " found on " + deviceName + ", possible driver or hardware issue");
                    else
                      edebug("Invalid solution " + currentHWErrors.incrementAndGet() + " found on " + deviceName + ", possible driver or hardware issue");
                  }
                }

                resetBuffer = true;
              }
            }

            if(resetBuffer) {
              buffer[bufferIndex].put(EMPTY_BUFFER, 0, 4 * OUTPUTS);
              buffer[bufferIndex].position(0);
              CL10.clEnqueueWriteBuffer(queue, output[bufferIndex], CL10.CL_FALSE, 0, buffer[bufferIndex], null, null);
            }

            if(submittedBlock) {
              if(!longPoll) {
                edebug("Forcing getwork update due to block submission");
                currentWork.forceUpdate();
              }
            }
          }

          skip = false;

          bufferIndex = (bufferIndex == 0) ? 1 : 0;

          workSizeTemp.put(0, workSize);
          currentWork.update(workSizeTemp.get(0) * loops * vectors);

          System.arraycopy(currentWork.midstate, 0, midstate2, 0, 8);

          sharound(midstate2, 0, 1, 2, 3, 4, 5, 6, 7, currentWork.data[16], 0x428A2F98);
          sharound(midstate2, 7, 0, 1, 2, 3, 4, 5, 6, currentWork.data[17], 0x71374491);
          sharound(midstate2, 6, 7, 0, 1, 2, 3, 4, 5, currentWork.data[18], 0xB5C0FBCF);

          int fW0 = currentWork.data[16] + (rot(currentWork.data[17], 7) ^ rot(currentWork.data[17], 18) ^
                    (currentWork.data[17] >>> 3));
          int fW1 = currentWork.data[17] + (rot(currentWork.data[18], 7) ^ rot(currentWork.data[18], 18) ^
                    (currentWork.data[18] >>> 3)) + 0x01100000;
          int fW2 = currentWork.data[18] + (rot(fW0, 17) ^ rot(fW0, 19) ^ (fW0 >>> 10));
          int fW3 = 0x11002000 + (rot(fW1, 17) ^ rot(fW1, 19) ^ (fW1 >>> 10));
          int fW15 = 0x00000280 + (rot(fW0, 7) ^ rot(fW0, 18) ^ (fW0 >>> 3));
          int fW01r = fW0 + (rot(fW1, 7) ^ rot(fW1, 18) ^ (fW1 >>> 3));

          int fcty_e = currentWork.midstate[4] + (rot(midstate2[1], 6) ^ rot(midstate2[1], 11) ^ rot(midstate2[1], 25)) +
                       (midstate2[3] ^ (midstate2[1] & (midstate2[2] ^ midstate2[3]))) + 0xe9b5dba5;
          int fcty_e2 = (rot(midstate2[5], 2) ^ rot(midstate2[5], 13) ^ rot(midstate2[5], 22)) + ((midstate2[5] & midstate2[6]) |
                        (midstate2[7] & (midstate2[5] | midstate2[6])));

          int fcty_e_plus_e2 = fcty_e + fcty_e2;
          int fcty_e_plus_state0 = fcty_e + currentWork.midstate[0];

          kernel.setArg(0, fW0)
                .setArg(1, fW1)
                .setArg(2, fW2)
                .setArg(3, fW3)
                .setArg(4, fW15)
                .setArg(5, fW01r)
                .setArg(6, fcty_e_plus_e2)
                .setArg(7, fcty_e_plus_state0)
                .setArg(8, currentWork.midstate[0])
                .setArg(9, currentWork.midstate[1])
                .setArg(10, currentWork.midstate[2])
                .setArg(11, currentWork.midstate[3])
                .setArg(12, currentWork.midstate[4])
                .setArg(13, currentWork.midstate[5])
                .setArg(14, currentWork.midstate[6])
                .setArg(15, currentWork.midstate[7])
                .setArg(16, midstate2[1])
                .setArg(17, midstate2[2])
                .setArg(18, midstate2[3] + 0xB956c25b)
                .setArg(19, midstate2[5])
                .setArg(20, midstate2[6])
                .setArg(21, midstate2[7])
                .setArg(22, (int)(currentWork.base / loops / vectors))
                .setArg(23, output[bufferIndex]);

          err = CL10.clEnqueueNDRangeKernel(queue, kernel, 1, null, workSizeTemp, localWorkSize, null, null);

          if(err !=  CL10.CL_SUCCESS && err != CL10.CL_INVALID_KERNEL_ARGS) {
            error("Failed to queue kernel, error " + err);
            System.exit(0);
          } else {
            if(err == CL10.CL_INVALID_KERNEL_ARGS) {
              debug("Spurious CL_INVALID_KERNEL_ARGS, ignoring");
              skip = true;
            } else {
              hashCount.addAndGet(workSizeTemp.get(0) * loops * vectors);
              deviceHashCount.addAndGet(workSizeTemp.get(0) * loops * vectors);
              currentWork.base += workSizeTemp.get(0) * loops * vectors;
              runs.incrementAndGet();
              err = CL10.clEnqueueReadBuffer(queue, output[bufferIndex], CL10.CL_TRUE, 0, buffer[bufferIndex], null, null);

              if(err != CL10.CL_SUCCESS)
                error("Failed to queue read buffer, error " + err);
            }
          }
        }
      }

      class GetWorkParser {
        final int[] data = new int[32];
        final int[] midstate = new int[8];
        final long[] target = new long[8];

        StringBuilder dataOutput = new StringBuilder(8*32 + 1);
        Formatter dataFormatter = new Formatter(dataOutput);

        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode getWorkMessage = mapper.createObjectNode();

        long lastPulled = 0;
        long base = 0;

        AtomicReference<JsonNode> longPollIncoming = new AtomicReference<JsonNode>(null);
        LinkedBlockingDeque<ObjectNode> sendWorkIncoming = new LinkedBlockingDeque<ObjectNode>();

        GetWorkParser() {
          new Thread(new sendWorkAsync(), "DiabloMiner Async Send Work for " +
                Thread.currentThread().getName().replace("DiabloMiner ", "")).start();

          getWorkMessage.put("method", "getwork");
          getWorkMessage.putArray("params");
          getWorkMessage.put("id", 1);

          getWork(false);
        }

        void forceUpdate() {
          ExecutionState[] executions;

          for(int i = 0; i < deviceStatesCount; i++) {
            executions = deviceStates.get(i).executions;
            for(int j = 0; j < EXECUTION_TOTAL; j++)
              executions[j].currentWork.lastPulled = 0;
          }
        }

        void update(long delta) {
          if(longPoll && longPollIncoming.get() != null) {
            getWorkAsync();
          } else if(base + delta > TWO32) {
            debug("Forcing getwork update due to nonce saturation");
            getWork(true);
          } else if(lastPulled + getWorkRefresh < getNow()) {
            getWork(false);
          }
        }

        void getWork(boolean nonceSaturation) {
          try {
            parse(doJSONRPC(bitcoind, userPass, mapper, getWorkMessage, true));
          } catch(IOException e) {
            error("Cannot connect to Bitcoin: " + e.getLocalizedMessage());

            if(nonceSaturation) {
              lastPulled = getNow();
              base = 0;

              data[17] = Integer.reverseBytes(Integer.reverseBytes(data[17]) + 1);

              return;
            }
          }

          lastPulled = getNow();
          base = 0;
        }

        void getWorkAsync() {
          JsonNode json = longPollIncoming.getAndSet(null);

          parse(json);
          lastPulled = getNow();
          base = 0;
        }

        void sendWork(int nonce) {
          data[19] = nonce;

          ObjectNode sendWorkMessage = mapper.createObjectNode();
          sendWorkMessage.put("method", "getwork");
          ArrayNode params = sendWorkMessage.putArray("params");
          params.add(encodeBlock());
          sendWorkMessage.put("id", 1);

          sendWorkIncoming.add(sendWorkMessage);
        }

        JsonNode doJSONRPC(URL bitcoind, String userPassword, ObjectMapper mapper, ObjectNode requestMessage, boolean timeout) throws IOException {
        	HttpURLConnection connection;

          if(proxy == null)
            connection = (HttpURLConnection) bitcoind.openConnection();
          else
            connection = (HttpURLConnection) bitcoind.openConnection(proxy);

          if(timeout)
            connection.setConnectTimeout(15000);

          connection.setRequestProperty("Authorization", userPassword);
          connection.setRequestProperty("Accept-Encoding", "gzip,deflate");
          connection.setRequestProperty("Content-Type", "application/json");
          connection.setRequestProperty("Cache-Control", "no-cache");
          connection.setDoOutput(true);

          OutputStream requestStream = connection.getOutputStream();
          Writer request = new OutputStreamWriter(requestStream);
          request.write(requestMessage.toString());
          request.close();
          requestStream.close();

          ObjectNode responseMessage = null;

          InputStream responseStream = null;

          try {
            if("getwork".equals(requestMessage.get("method").getTextValue())) {
              String xLongPolling = connection.getHeaderField("X-Long-Polling");

              if(xLongPolling != null) {
                if(xLongPolling.startsWith("http"))
                  bitcoindLongPoll = new URL(xLongPolling);
                else if(xLongPolling.startsWith("/"))
                  bitcoindLongPoll = new URL(bitcoind.getProtocol(), bitcoind.getHost(), bitcoind.getPort(),
                        xLongPolling);
                else
                  bitcoindLongPoll = new URL(bitcoind.getProtocol(), bitcoind.getHost(), bitcoind.getPort(),
                        (bitcoind.getFile() + "/" + xLongPolling).replace("//", "/"));

                if(longPoll == false) {
                  new Thread(new getWorkAsync(), "DiabloMiner Long Poll for " +
                        Thread.currentThread().getName().replace("DiabloMiner ", "")).start();

                  getWorkRefresh = 60000;
                  longPoll = true;

                  debug("Enabling long poll support");
                }
              }
            }

            if(connection.getContentEncoding() != null) {
              if(connection.getContentEncoding().equalsIgnoreCase("gzip"))
                responseStream = new GZIPInputStream(connection.getInputStream());
              else if(connection.getContentEncoding().equalsIgnoreCase("deflate"))
                responseStream = new InflaterInputStream(connection.getInputStream());
            } else {
              responseStream = connection.getInputStream();
            }

            if(responseStream == null)
              throw new IOException("Drop to error handler");

            Object output = mapper.readTree(responseStream);

            if(NullNode.class.equals(output.getClass()))
              throw new IOException("Bitcoin returned unparsable JSON") ;
            else
              responseMessage = (ObjectNode) output;

            responseStream.close();
          } catch (JsonProcessingException e) {
            throw new IOException("Bitcoin returned unparsable JSON");
          } catch (IOException e) {
            InputStream errorStream = null;
            IOException e2 = null;

            if(connection.getErrorStream() == null)
              throw new IOException("Bitcoin disconnected during response: "
                    + connection.getResponseCode() + " " + connection.getResponseMessage());

            if(connection.getContentEncoding() != null) {
              if(connection.getContentEncoding().equalsIgnoreCase("gzip"))
                errorStream = new GZIPInputStream(connection.getErrorStream());
              else if(connection.getContentEncoding().equalsIgnoreCase("deflate"))
                errorStream = new InflaterInputStream(connection.getErrorStream());
            } else {
              errorStream = connection.getErrorStream();
            }

            if(errorStream == null)
              throw new IOException("Bitcoin disconnected during response: "
                  + connection.getResponseCode() + " " + connection.getResponseMessage());

            byte[] errorbuf = new byte[8192];
            errorStream.read(errorbuf);
            String error = new String(errorbuf).trim();

            if(error.startsWith("{")) {
              try {
                Object output = mapper.readTree(error);

                if(NullNode.class.equals(output.getClass()))
                  throw new IOException("Bitcoin returned error: " + error);
                else
                  responseMessage = (ObjectNode) output;

                if(responseMessage.get("error") != null) {
                  if(responseMessage.get("error").get("message") != null &&
                        responseMessage.get("error").get("message").getValueAsText() != null) {
                    error = responseMessage.get("error").get("message").getValueAsText().trim();
                    e2 = new IOException("Bitcoin returned error message: " + error);
                  } else if(responseMessage.get("error").getValueAsText() != null) {
                    error = responseMessage.get("error").getValueAsText().trim();

                    if(!"null".equals(error) && !"".equals(error))
                      e2 = new IOException("Bitcoin returned error message: " + error);
                  }
                }
              } catch(JsonProcessingException f) {
                e2 = new IOException("Bitcoin returned unparsable JSON");
              }
            } else {
              e2 = new IOException("Bitcoin returned error message: " + error);
            }

            errorStream.close();

            if(responseStream != null)
              responseStream.close();

            if(e2 == null)
              e2 = new IOException("Bitcoin returned an error, but with no message");

            throw e2;
          }

          if(responseMessage.get("error") != null) {
            if(responseMessage.get("error").get("message") != null &&
                  responseMessage.get("error").get("message").getValueAsText() != null) {
              String error = responseMessage.get("error").get("message").getValueAsText().trim();
                throw new IOException("Bitcoin returned error message: " + error);
            } else if(responseMessage.get("error").getValueAsText() != null) {
              String error = responseMessage.get("error").getValueAsText().trim();

              if(!"null".equals(error) && !"".equals(error))
                throw new IOException("Bitcoin returned error message: " + error);
            }
          }

          JsonNode result = responseMessage.get("result");

          if(result == null)
            throw new IOException("Bitcoin did not return a result or an error");

          return result;
        }

        void parse(JsonNode responseMessage) {
          String datas = responseMessage.get("data").getValueAsText();
          String midstates = responseMessage.get("midstate").getValueAsText();
          String targets = responseMessage.get("target").getValueAsText();

          String parse;

          for(int i = 0; i < 32; i++) {
            parse = datas.substring(i*8, (i*8)+8);
            data[i] = Integer.reverseBytes((int)Long.parseLong(parse, 16));
          }

          for(int i = 0; i < 8; i++) {
            parse = midstates.substring(i*8, (i*8)+8);
            midstate[i] = Integer.reverseBytes((int)Long.parseLong(parse, 16));
          }

          for(int i = 0; i < 8; i++) {
            parse = targets.substring(i*8, (i*8)+8);
            target[i] = (Long.reverseBytes(Long.parseLong(parse, 16) << 16)) >>> 16;
          }
        }

        String encodeBlock() {
          dataOutput.setLength(0);

          dataFormatter.format(
                "%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x" +
                "%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x",
                Integer.reverseBytes(data[0]), Integer.reverseBytes(data[1]),
                Integer.reverseBytes(data[2]), Integer.reverseBytes(data[3]),
                Integer.reverseBytes(data[4]), Integer.reverseBytes(data[5]),
                Integer.reverseBytes(data[6]), Integer.reverseBytes(data[7]),
                Integer.reverseBytes(data[8]), Integer.reverseBytes(data[9]),
                Integer.reverseBytes(data[10]), Integer.reverseBytes(data[11]),
                Integer.reverseBytes(data[12]), Integer.reverseBytes(data[13]),
                Integer.reverseBytes(data[14]), Integer.reverseBytes(data[15]),
                Integer.reverseBytes(data[16]), Integer.reverseBytes(data[17]),
                Integer.reverseBytes(data[18]), Integer.reverseBytes(data[19]),
                Integer.reverseBytes(data[20]), Integer.reverseBytes(data[21]),
                Integer.reverseBytes(data[22]), Integer.reverseBytes(data[23]),
                Integer.reverseBytes(data[24]), Integer.reverseBytes(data[25]),
                Integer.reverseBytes(data[26]), Integer.reverseBytes(data[27]),
                Integer.reverseBytes(data[28]), Integer.reverseBytes(data[29]),
                Integer.reverseBytes(data[30]), Integer.reverseBytes(data[31]));

          return dataOutput.toString();
        }

        class getWorkAsync implements Runnable {
          public void run() {
            while(running) {
              try {
                longPollIncoming.set(doJSONRPC(bitcoindLongPoll, userPass, mapper, getWorkMessage, false));
                debug("Long poll getwork returned");
              } catch(IOException e) {
                error("Cannot connect to Bitcoin: " + e.getLocalizedMessage());
                while(longPollIncoming.get() == null) {
                  try {
                    longPollIncoming.set(doJSONRPC(bitcoind, userPass, mapper, getWorkMessage, true));
                  } catch (IOException f) {
                    try {
                      Thread.sleep(1000);
                    } catch (InterruptedException e1) { }
                  }
                }
              }

              while(longPollIncoming.get() != null)
                try {
                  Thread.sleep(1000);
                } catch (InterruptedException e) {}
            }
          }
        }

        class sendWorkAsync implements Runnable {
          public void run() {
            while(running) {
              ObjectNode sendWork = null;
              boolean error = false;

              try {
                sendWork = sendWorkIncoming.take();
              } catch (InterruptedException e) { }

              while(sendWork != null) {
                try {
                  boolean accepted = doJSONRPC(bitcoind, userPass, mapper, sendWork, true).getBooleanValue();

                  if(accepted) {
                    info("Accepted block " + currentBlocks.incrementAndGet() + " found on " + deviceName);
                  } else {
                    info("Rejected block " + currentRejects.incrementAndGet() + " found on " + deviceName);
                  }

                  sendWork = null;
                } catch (IOException e) {
                  if(!error) {
                    error("Cannot connect to Bitcoin: " + e.getLocalizedMessage());
                    error = true;

                    try {
                      Thread.sleep(1000);
                    } catch (InterruptedException e1) { }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
