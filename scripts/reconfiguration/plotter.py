#!/usr/bin/env python

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

TYPE_MAP = {
  "tps": "THROUGHPUT",
  "lat": "LATENCY"
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
LOG.setLevel(logging.DEBUG)



def getParser():
    parser = argparse.ArgumentParser()
    parser.add_argument("-t","--type", dest="type", choices=["cfd","boxplot","bar","line"], required=True, help="The type of graph to plot")
    
    parser.add_argument("--no-display", action="store_true", help="Do not display the graph")
    parser.add_argument("-v","--save", dest="save",  help="filename to save the generated plot")
   
    parser.add_argument("--tsd", action="store_true", help="Plot Time Series Data (intervals)")
    parser.add_argument("-r","--recursive", dest="recursive", action="store_true", help="Check directories recursively")
    parser.add_argument("--reconfig", dest="reconfig", action="store_true", help="Plot reconfig bars") 
     
    parser.add_argument("-s","--show", dest="show", choices=["tps","lat"], required=True, help="Show this data type")            
    
    group = parser.add_mutually_exclusive_group() 
    group.add_argument("-d","--dir", dest="dir", help="The directory to load files from")            
    group.add_argument("-f","--file", dest="file", help="The file to graph")            
    
    parser.add_argument("--filter", dest="filter", help="Filter OUT files containing these stings (comma delim)")
    
    parser.add_argument("--title", dest="title", help="title for the figure")
    parser.add_argument("--ylabel", dest="ylabel", help="label for the y-axis")
    parser.add_argument("--xlabel", dest="xlabel", help="label for the x-axis")
    return parser

def getReconfigEvents(hevent_log):
    events = []
    with open(hevent_log, "r") as f:
        protocol = ''
        for line in f:
            items = line.split(",")
            ts = np.int64(items[0])    
            if "SYSPROC" in line:
                event = "TXN"
                protocol = items[len(items)-1].strip().split("=")[1]
            elif "INIT" in line:
                event = "INIT"
            elif "END" in line:
                event = "END"
            events.append((ts,event,protocol))
    return events

def addReconfigEvent(df, reconfig_events):
    df['RECONFIG'] = ''
    for event in reconfig_events:
      ts = event[0]
      #find the index last row that has a smaller physical TS
      _i = df[(df['TIMESTAMP'] <= ts ) ][-1:].index

      #if we have new event set, otherwise append      
      if df.RECONFIG[_i] == "":
         df.RECONFIG[_i] = event[1]
      else:
         df.RECONFIG[_i] = df.RECONFIG[_i] + "-" + event[1]

            
def plotGraph(args):

    if args.ylabel != None:
        plot.ylabel(args.ylabel)
    else:
        plot.ylabel(args.show)
    if args.xlabel != None:
        plot.xlabel(args.xlabel)
    else:
        plot.xlabel("Elapsed Time (s)")
    
    if args.title != None:
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


## ==============================================
## tsd
## ==============================================
def plotTSD(args, files, ax):
    dfs = [ (d, pandas.DataFrame.from_csv(d)) for d in files if "interval" in d]
    data = {}
    colormap = {}
    init_legend = "Reconfig Init"
    end_legend = "Reconfig End"
    for x,(_file, df) in enumerate(dfs):
        name = os.path.basename(_file).split("-interval")[0] 
        if args.recursive:
            name = "%s-%s" % (name, os.path.dirname(_file).rsplit(os.path.sep,1)[1])   
            LOG.info("Name now : %s " % name)
        color = COLORS[x % len(COLORS)]
	linestyle = LINE_STYLES[x % len(LINE_STYLES)]
        colormap[name] = color   
        data[name] = df[TYPE_MAP[args.show]].values
        if args.reconfig:
            reconfig_events = getReconfigEvents(_file.replace("interval_res.csv", "hevent.log"))
            addReconfigEvent(df, reconfig_events)
            if len(df[df.RECONFIG.str.contains('TXN')]) == 1:
                ax.axvline(df[df.RECONFIG.str.contains('TXN')].index[0], color=color, lw=1.5, linestyle="--",label=init_legend)
                ax.axvline(df[df.RECONFIG.str.contains('END')].index[0], color=color, lw=1.5, linestyle=":",label=end_legend)
                init_legend = None
                end_legend = None
            else:
                LOG.error("Multiple reconfig events not currently supported")
             
        if args.type == "line":
            #plot the line with the same color 
            ax.plot(df.index, df[TYPE_MAP[args.show]], color=color,label=name,ls=linestyle, lw=2.0)
    plotFrame = pandas.DataFrame(data=data)
    if args.type == "line":
        pass
        #plotFrame.plot(ax=ax, colormap=colormap)
    elif args.type == "boxplot":
        plotFrame.boxplot(ax=ax)
    else:
        raise Exception("unsupported plot type for tsd : " + args.type)  

    plotGraph(args)
## ==============================================
## main
## ==============================================
def plotter(args, files):
    plot.figure()
    ax = plot.subplot(111)
    
    if args.tsd:
        plotTSD(args, files, ax)   
    else:
        raise Exception("Only TSD supported")
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
