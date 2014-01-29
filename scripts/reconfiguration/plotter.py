#!/usr/bin/env python
import matplotlib
#matplotlib.use('Agg')
import os
import sys
import csv
import logging
import matplotlib.pyplot as plot
import pandas
import pylab
import logging
import getopt
import argparse
import numpy as np
import re
from texGraphDefault import *

OPT_GRAPH_WIDTH = 1200
OPT_GRAPH_HEIGHT = 600
OPT_GRAPH_DPI = 100

INTERVAL_TYPE_MAP = {
  "tps": "THROUGHPUT",
  "lat": "LATENCY",
  "lat50": "LATENCY_50",
  "lat95": "LATENCY_95",
  "lat99": "LATENCY_99",
}

RESULTS_TYPE_MAP = {
  "tps": "TXNTOTALPERSECOND",
  "lat": "TOTALAVGLATENCY",
}

## ==============================================
## LOGGING CONFIGURATION
## ==============================================

LOG = logging.getLogger(__name__)
LOG_handler = logging.StreamHandler()
LOG_formatter = logging.Formatter(fmt='%(asctime)s [%(funcName)s:%(lineno)03d] %(levelname)-5s: %(message)s',
                                  datefmt='%m-%d-%Y %H:%M:%S')
LOG_handler.setFormatter(LOG_formatter)
LOG.addHandler(LOG_handler)
LOG.setLevel(logging.INFO)



def getParser():
    parser = argparse.ArgumentParser()
    parser.add_argument("-t","--type", dest="type", choices=["cfd","boxplot","bar","line","aborts"], required=True, help="The type of graph to plot")
    
    parser.add_argument("--no-display", action="store_true", help="Do not display the graph")
    parser.add_argument("-v","--save", dest="save",  help="filename to save the generated plot")
   
    parser.add_argument("--tsd", action="store_true", help="Plot Time Series Data (intervals)")
    parser.add_argument("-r","--recursive", dest="recursive", action="store_true", help="Check directories recursively")
    parser.add_argument("--reconfig", dest="reconfig", action="store_true", help="Plot reconfig bars") 
    parser.add_argument("--subplots", dest="subplots", action="store_true", help="Plot subplots") 
     
    parser.add_argument("-s","--show", dest="show", choices=["tps","lat","lat50","lat95","lat99","latall"], required=True, help="Show this data type")            
    
    group = parser.add_mutually_exclusive_group() 
    group.add_argument("-d","--dir", dest="dir", help="The directory to load files from")            
    group.add_argument("-f","--file", dest="file", help="The file to graph")            
    
    parser.add_argument("--filter", dest="filter", help="Filter OUT files containing these stings (comma delim)")
    
    parser.add_argument("--title", dest="title", help="title for the figure")
    parser.add_argument("--ylabel", dest="ylabel", help="label for the y-axis")
    parser.add_argument("--xlabel", dest="xlabel", help="label for the x-axis")
    return parser

def getTxnStats(txncounter_log):
    df = pandas.DataFrame.from_csv(txncounter_log)
    return df.sum()

def getAbortedTxns(df):
    #ABORTED is user
    return sum(df[['REJECTED','ABORT_UNEXPECTED','ABORT_GRACEFUL','ABORT_SPECULATIVE']])

def getReconfigEvents(hevent_log):
    events = []
    if not os.path.exists(hevent_log):
        return None
    with open(hevent_log, "r") as f:
        protocol = ''
        for line in f:
            try:
                if "," in line:
                    items = line.split(",")
                    ts = np.int64(items[0])    
                    if "SYSPROC" in line:
                        event = "TXN"
                        protocol = items[len(items)-1].strip().split("=")[1]
                    elif "INIT" in line and "REPORT" not in line:
                        event = "INIT"
                    elif "END" in line:
                        event = "END"
                    else:
                        event = "UNKNOWN"
                    if event != "UNKNOWN":
                      events.append((ts,event,protocol))

            except ValueError:
               LOG.debug("Skipping line to value error %s"%line) 
                
    return events

def getReconfigDetails(hevent_log):
    details = {}
    if not os.path.exists(hevent_log):
        return None
    pulledSize = 0    
    with open(hevent_log, "r") as f:
      for line in f:
        try:
          if "REPORT_TOTAL_PULL_SIZE" in line:
            vals = {k.strip():v.strip() for (k,v) in [d.split("=") for d in line.split(",") if "=" in d]}
            if "KB" in vals:
              pulledSize+= int(vals["KB"])
        except:
          print "Error in line", line

    details['pulledKB'] = pulledSize
    return details

def getReconfigStartEnd(reconfig_events):
  start = 0
  end = 0
  for r in reconfig_events:
    if r[1] == "TXN":
      start = r[0]
    elif r[1] == "END":
      end = r[0]
  return (start,end)      

def addReconfigEvent(df, reconfig_events):
    df['RECONFIG'] = ''
    df['IN_RECONFIG'] = False
    df['MISSING_DATA'] = False
    start, end = getReconfigStartEnd(reconfig_events)
    if not reconfig_events:
        return 
    for event in reconfig_events:
      ts = event[0]
      if 'stop' in event[2]:
        ts+=1000
      if event[1] == 'END' and 'stop' in event[2]:
        ts += 1000
      #find the index last row that has a smaller physical TS
      _i = df[(df['TIMESTAMP'] <= (ts) ) ][-1:].index

      #LATENCY.isnull()
      #if we have new event set, otherwise append      
      if df.RECONFIG[_i] == "":
         df.RECONFIG[_i] = event[1]
      else:
         df.RECONFIG[_i] = df.RECONFIG[_i] + "-" + event[1]
    df['IN_RECONFIG'][(df.TIMESTAMP >= start-1000) & (df.TIMESTAMP <= end)] = True
    df['MISSING_DATA'] = df.LATENCY.isnull()
    df['DOWNTIME'] = df['MISSING_DATA'].sum()
    df['RECONFIG_TIME'] = end-start
    #df.groupby('IN_RECONFIG')['LATENCY','LATENCY_50','LATENCY_95','LATENCY_99','THROUGHPUT'].mean()
    
def getIntStats(interval_file):
  df = pandas.DataFrame.from_csv(interval_file)  
  reconfig_events = getReconfigEvents(interval_file.replace("interval_res.csv", "hevent.log"))
  reconfig_details= getReconfigDetails(interval_file.replace("interval_res.csv", "hevent.log"))
  if reconfig_events is None:
    return None
  addReconfigEvent(df,reconfig_events)

  df['KB_PULLED'] = reconfig_details['pulledKB']
  return df
   
def printDFStat(df):
  print "="*80
  print "Mean"
  print
  print df.groupby('IN_RECONFIG')['LATENCY','LATENCY_50','LATENCY_95'
  ,'LATENCY_99','THROUGHPUT','DOWNTIME','RECONFIG_TIME','KB_PULLED'].mean()
  print ""
  print "Median"
  print df.groupby('IN_RECONFIG')['LATENCY','LATENCY_50','LATENCY_95','LATENCY_99','THROUGHPUT','DOWNTIME','RECONFIG_TIME','KB_PULLED'].median()
  print ""

def getDFState(df,show):
  if show == "mean":    
    return df.groupby('IN_RECONFIG')['LATENCY','LATENCY_50','LATENCY_95','LATENCY_99','THROUGHPUT','DOWNTIME','RECONFIG_TIME','KB_PULLED'].mean()
  elif show == "median":
    return df.groupby('IN_RECONFIG')['LATENCY','LATENCY_50','LATENCY_95','LATENCY_99','THROUGHPUT','DOWNTIME','RECONFIG_TIME','KB_PULLED'].median()
  else:
    raise Exception("doh")

def getDirStat(directory,reconfigs, stops, show="mean", keepfilter=None, display=False):

  interval_files = [os.path.join(directory,f) for f in os.listdir(directory) if "interval_res" in f]
  if keepfilter:
    interval_files = [f for f in interval_files if keepfilter in f]

  if len(interval_files) == 0:
    print "No interval file found in %s %s" % (directory,','.join(os.listdir(directory)))
    return
  for interval_file in interval_files:  
    print  
    df = getIntStats(interval_file)
    print interval_file 

    if df is not None and "recon" in interval_file:
      alt_version = interval_file.replace("reconfig","stopcopy")
      if os.path.exists(alt_version):
        #only keep if we have both version of the same experiment
        adf = getIntStats(alt_version)
        reconfigs.append(getDFState(df,show))
        stops.append(getDFState(adf,show))
    elif df is not None and "stopcopy" in interval_file:
      alt_version = interval_file.replace("stopcopy", "reconfig")
      print "Adding to stops", interval_file
      stops.append(getDFState(df,show))

    if display and df is not None:
      printDFStat(df)

      if os.path.exists(alt_version):
        adf = getIntStats(alt_version)
        print alt_version
        printDFStat(adf)
      else:
        print "no alt version found"
      print ""
  
def exploreDir(d, keepfilter=None):
  reconfigs = []
  stops = []
  print "-- --  " * 15
  for r,dirs,files in os.walk(d):
    print ",".join(dirs)
    print ""
    for d in dirs:
      if "reconfig" in d:
        getDirStat(os.path.join(r,d),reconfigs,stops,"mean",keepfilter)
        print "-- --  " * 15
  return {"reconfigs": reconfigs, "stops": stops}      


def getChange(record,attribute):
  return ((record.ix[True][attribute]- record.ix[False][attribute])/record.ix[False][attribute])*100.00

  

def calcChange(res):
  t = []
  l = []
  mb = []
  for data in res['reconfigs']:
    if 'THROUGHPUT' not in data:
      continue
    if len(data) < 2:
      continue
    throughputDrop = getChange(data,'THROUGHPUT')
    t.append(throughputDrop*-1)
    latencyIncrease = getChange(data, 'LATENCY')
    l.append(latencyIncrease)
    mb_pulled = data.ix[True]['KB_PULLED']/1024
    mb.append(mb_pulled)
    print ",%s,%s,%s" % (throughputDrop, latencyIncrease, mb_pulled)
  fit = np.polyfit(mb,t,1)
  fit_fn = np.poly1d(fit)

  plot.plot(mb,t,'o', mb,fit_fn(mb),'--k')
  plot.ylabel("Throughput % Increase During Reconfiguration")
  plot.xlabel("TPC-C MB Reconfigured")
  #plot.show()
  #return (t,mb)


def plotGraph(args):

    if args.ylabel != None:
        plot.ylabel(args.ylabel)
    else:
        if args.type == "aborts":
            plot.ylabel("Aborts")
        elif "lat" in args.show:
            plot.ylabel("Latency(ms)")
        else:
            plot.ylabel(args.show)
    if args.xlabel != None:
        plot.xlabel(args.xlabel)
    elif args.type =="aborts":
        plot.xlabel("")
    else:
        plot.xlabel("Elapsed Time (s)")
    
    if args.subplots:
        pass
    elif args.title != None:
        plot.title(args.title)
    else:
        #add \n every 128 
        #via http://stackoverflow.com/questions/2657693/insert-a-newline-character-every-64-characters-using-python
        title = re.sub("(.{128})", "\\1\n", str(args), 0, re.DOTALL) 
        plot.title(title, fontsize=12)
    plot.legend(loc="best",prop={'size':12})

    if args.save:
        plot.savefig("%s.png" % args.save)
    if not args.no_display:
        plot.show()


def plotCompare(args, files, ax):
    LOG.info("Plot results : %s \n\n" % args)
    dfs = [ (d, pandas.DataFrame.from_csv(d,index_col=1)) for d in files if "interval_res" in d]
    data = {}
    x = 0
    if len(dfs) == 0:
        raise Exception("No files with 'results' in name : \n %s " % ('\n  '.join(files))) 
    for (_file, df) in dfs:
        name = os.path.basename(_file).split("-results")[0] 
        base_name = name
        LOG.info("Examining file : %s with name %s "  % (_file,base_name))
        if args.recursive:
            name = "%s-%s" % (name, os.path.dirname(_file).rsplit(os.path.sep,1)[1])   
            base_name = name
	    if args.type == "aborts":
                LOG.info("Plotting Aborts")
                txnstats = getTxnStats(_file.replace("interval_res.csv", "txncounters.csv"))
                print txnstats
                if len(txnstats) > 0:
                    aborts = getAbortedTxns(txnstats)
                else:
                    aborts = 0
                print "aborts", name, aborts
                color = COLORS[x % len(COLORS)]
            #print df
            if args.type == "line":
                #plot the line with the same color 
                ax.plot(df.index, data[name], color=color,label=name,ls=linestyle, lw=2.0)
            elif args.type == "bar":
                ax.bar(x, data[name], color=color, label=name)
            elif args.type == "aborts":
                ax.bar(x, aborts, color=color, label=name)
            x+=1 # FOR
    plotFrame = pandas.DataFrame(data=data)
    if args.type == "line" or args.type == "bar" or args.type == "aborts":
        pass
        #plotFrame.plot(ax=ax )
    elif args.type == "boxplot":
        plotFrame.boxplot(ax=ax, notch=1, sym='', vert=1, whis=1.5, 
              positions=None, widths=None, patch_artist=False, bootstrap = 5000)
    else:
        raise Exception("unsupported plot type : " + args.type)  

    plotGraph(args)


## ==============================================
## plot aggregate results 
## ==============================================
def plotResults(args, files, ax):
    LOG.info("Plot results : %s \n\n" % args)
    dfs = [ (d, pandas.DataFrame.from_csv(d,index_col=1)) for d in files if "results" in d]
    data = {}
    x = 0
    if len(dfs) == 0:
        raise Exception("No files with 'results' in name : \n %s " % ('\n  '.join(files))) 
    for (_file, df) in dfs:
        name = os.path.basename(_file).split("-results")[0] 
        base_name = name
        LOG.info("Examining file : %s with name %s "  % (_file,base_name))
        if args.recursive:
            name = "%s-%s" % (name, os.path.dirname(_file).rsplit(os.path.sep,1)[1])   
            base_name = name
	    if args.type == "aborts":
                LOG.info("Plotting Aborts")
                txnstats = getTxnStats(_file.replace("interval_res.csv", "txncounters.csv"))
                print txnstats
                if len(txnstats) > 0:
                    aborts = getAbortedTxns(txnstats)
                else:
                    aborts = 0
                print "aborts", name, aborts
                color = COLORS[x % len(COLORS)]
        for show_var in args.show_vars:
            color = COLORS[x % len(COLORS)]
            linestyle = LINE_STYLES[x % len(LINE_STYLES)]
            if len(args.show_vars) > 1:
                name = "%s-%s" % (base_name,show_var)
            data[name] = df[RESULTS_TYPE_MAP[show_var]].values
            if args.reconfig:
                reconfig_events = getReconfigEvents(_file.replace("interval_res.csv", "hevent.log"))
                addReconfigEvent(df, reconfig_events)
                if len(df[df.RECONFIG.str.contains('TXN')]) == 1:
                    LOG.error("TODO annotate this has reconfig")
                else:
                    LOG.error("Multiple reconfig events not currently supported")
                 
            #print df
            if args.type == "line":
                #plot the line with the same color 
                ax.plot(df.index, data[name], color=color,label=name,ls=linestyle, lw=2.0)
            elif args.type == "bar":
                ax.bar(x, data[name], color=color, label=name)
            elif args.type == "aborts":
                ax.bar(x, aborts, color=color, label=name)
            x+=1 # FOR
    plotFrame = pandas.DataFrame(data=data)
    if args.type == "line" or args.type == "bar" or args.type == "aborts":
        pass
        #plotFrame.plot(ax=ax )
    elif args.type == "boxplot":
        plotFrame.boxplot(ax=ax, notch=1, sym='', vert=1, whis=1.5, 
              positions=None, widths=None, patch_artist=False, bootstrap = 5000)
    else:
        raise Exception("unsupported plot type : " + args.type)  

    plotGraph(args)

## ==============================================
## tsd
## ==============================================
def plotTSD(args, files, ax):
    dfs = [ (d, pandas.DataFrame.from_csv(d,index_col=1)) for d in files if "interval" in d]
    if args.subplots:
        f,axarr = plot.subplots(len(dfs), sharex=True, sharey=False)
    data = {}
    init_legend = "Reconfig Init"
    end_legend = "Reconfig End"
    x = 0
    for i,(_file, df) in enumerate(dfs):
        name = os.path.basename(_file).split("-interval")[0] 
        base_name = name
        if args.recursive:
            name = "%s-%s" % (name, os.path.dirname(_file).rsplit(os.path.sep,1)[1])   
            base_name = name
        if args.subplots:
            ax = axarr[i]
            ax.set_title(name)
        for show_var in args.show_vars:
            if args.subplots:
              color = "black"
            else:  
              color = COLORS[x % len(COLORS)]
            linestyle = LINE_STYLES[x % len(LINE_STYLES)]
            if len(args.show_vars) > 1:
                name = "%s-%s" % (base_name,show_var)
            data[name] = df[INTERVAL_TYPE_MAP[show_var]].values
            if args.reconfig:
                reconfig_events = getReconfigEvents(_file.replace("interval_res.csv", "hevent.log"))
                if reconfig_events:
                    addReconfigEvent(df, reconfig_events)
                    if len(df[df.RECONFIG.str.contains('TXN')]) == 1:
                        ax.axvline(df[df.RECONFIG.str.contains('TXN')].index[0], color=color, lw=1.5, linestyle="--",label=init_legend)
                        if any(df.RECONFIG.str.contains('END')):
                            ax.axvline(df[df.RECONFIG.str.contains('END')].index[0], color=color, lw=1.5, linestyle=":",label=end_legend)
                            end_legend = None
                        else:
                            LOG.error("*****************************************")
                            LOG.error("*****************************************")
                            LOG.error(" NO END FOUND %s " % name)
                            LOG.error("*****************************************")
                            LOG.error("*****************************************")
            
                        init_legend = None
                    elif len(df[df.RECONFIG.str.contains('TXN')]) < 1:
                        LOG.error("NO reconfig event found!")
                    else:
                        LOG.error("Multiple reconfig events not currently supported")
            print name     
            print "="*80
            print df
            #print df.groupby('IN_RECONFIG')['LATENCY','LATENCY_50','LATENCY_95','LATENCY_99','THROUGHPUT'].mean()
            print ""
            if args.type == "line":
                #plot the line with the same color 
                ax.plot(df.index, data[name], color=color,label=name,ls=linestyle, lw=2.0)
            x+=1 # FOR
    plotFrame = pandas.DataFrame(data=data)
    if args.type == "line":
        pass
        #plotFrame.plot(ax=ax )
    elif args.type == "boxplot":
        plotFrame.boxplot(ax=ax)
    else:
        raise Exception("unsupported plot type for tsd : " + args.type)  

    plotGraph(args)
## ==============================================
## main
## ==============================================
def plotter(args, files):

    if not args.subplots:
        plot.figure()
        ax = plot.subplot(111)
    else:
        ax = None
    
    print args
    if args.show == "latall":
        if args.tsd:
            keymap = INTERVAL_TYPE_MAP
        else:
            keymap = RESULTS_TYPE_MAP

        args.show_vars = [x for x in keymap if "lat" in x] 
    else:
        args.show_vars = [args.show]
    if args.tsd:
        plotTSD(args, files, ax) 
    elif args.type == "aborts":
        plotCompare(args, files, ax)  
    else:
        plotResults(args, files, ax)
    #LOG.debug("Files to plot %s" % (files))

    
    
if __name__=="__main__":
    parser = getParser()
    args = parser.parse_args()
    
    print args
    files = []
    if args.dir:
        if args.recursive:
            for _dir, _subdir, _files in os.walk(args.dir):
                files.extend([os.path.join(_dir,x) for x in _files])
                
        else:
            files = [os.path.join(args.dir,x) for x in os.listdir(args.dir)]
    else:
        files.append(args.file)
    if args.filter:
        for filt in args.filter.split(","):
            files = [f for f in  files if filt not in f]
    LOG.info("Files : %s " % "\n".join(files) ) 
    
    plotter(args, files)
    

## MAIN
