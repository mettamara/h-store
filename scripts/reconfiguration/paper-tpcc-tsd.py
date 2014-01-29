import pandas
import matplotlib
import matplotlib.pyplot as plot
import pylab
import plotter
import os
from texGraphDefault import *

LOG = plotter.LOG

def plotTSD(files,filedir,var,ylabel,xlabel, ylim=None,xtrim=None, filename=None):
  data = []
  labels = []
  reconfigs = []
  dfs = []
  for f in files:
    _file = os.path.join(filedir,f[1])
    df = pandas.DataFrame.from_csv(_file,index_col=1)
    if isinstance(xtrim,int):
      df = df[:xtrim]
    elif isinstance(xtrim,(list,tuple)):
      df = df[xtrim[0]:xtrim[1]]
    #print var
    #print df[var].values
    data.append(df[var].values)
    reconfig_events = plotter.getReconfigEvents(_file.replace("interval_res.csv", "hevent.log"))
    plotter.addReconfigEvent(df, reconfig_events)
    df.index = df.index / 1000
    reconfigs.append(df)
    labels.append(f[0])


  #  params['figure.figsize'] = calcShortFigSize()
  rcParams.update(params)
  pylab.rc("axes", linewidth=1.0)
  pylab.rc("lines", markeredgewidth=1.0)

  f = plot.figure()
  axarr =[]
  axarr.append(plot.subplot(211))
  axarr.append(plot.subplot(212))

  f,axarr = plot.subplots(len(data), sharex=True, sharey=True,)# figsize=calcShortFigSize())
  plot.title("", )
  plot.xlabel(xlabel,)
  #f.text(0.06, 0.5, 'common ylabel', ha='center', va='center', rotation='vertical', fontsize='18')
  
  pylab.ylim(ylim)

  #for tick in ax.yaxis.get_major_ticks():
  #  tick.label1.set_fontsize('16')
  #  tick.label2.set_fontsize('16')
  init_legend = "Reconfig Init"
  end_legend = "Reconfig End"
  color = "black"
  for j,(_d,r, name) in enumerate(zip(data,reconfigs,labels)):
    ax =axarr[j]
    if ylim:
      ax.set_ylim(ylim)

    if "Squall" not in name:
      lcolor ="red"
    else:
      lcolor ="blue"
    ax.plot(df.index, _d, label=name, lw=1.0, color= lcolor,)
    plot.subplots_adjust(hspace=0.001)
    ax.set_ylabel(ylabel,)
    #ax.set_title(name)
    ax.yaxis.set_major_locator(MaxNLocator(4))
    ax.legend(loc=0)#,prop={'size':8})

    if len(r[r.RECONFIG.str.contains('TXN')]) == 1:
      ax.axvline(r[r.RECONFIG.str.contains('TXN')].index[0], color=color, lw=1.0, linestyle="--",label=init_legend)
      if any(r.RECONFIG.str.contains('END')):
          ax.axvline(r[r.RECONFIG.str.contains('END')].index[0], color=color, lw=1.0, linestyle=":",label=end_legend)
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
   
  #labels = ax.set_xticklabels(labels , fontsize ='16')
  #labels = ax.set_yticklabels(['2000','2500','3000','3500','4000','4500'] , fontsize ='16')
  #labels = ax.get_xticklabels()
  #for label in ax.xaxis.get_ticklabels():
    #label.set_rotation(0)

  
  f.tight_layout()
  print params
  if filename:
    plot.savefig(filename, format = 'pdf')
  else:
    plot.show()

if __name__ == "__main__":
  import sys

  
  #contraction
  tpcc = [
    ( "Stop and Copy TPC-C" , "stopcopy-2b/tpcc-08p-tpc4ContractScale0.2-interval_res.csv"), 
    ( "Squall TPC-C" ,        "reconfig-2b/tpcc-08p-tpc4ContractScale0.2-interval_res.csv"), 
  ] 
  
  filedir = "/home/aelmore/out/tcontract16-scale02/out"
  if not os.path.isdir(filedir):
    raise Exception("Not a directory")
  var = "LATENCY"
  ylabel = "Latency (ms)"
  xlabel = "Elapsed Time (seconds)"
  plotTSD(tpcc,filedir,var, ylabel,xlabel, [0,2000],(20,180), "tpcTSDmeanLat-8to4partitions-16w-scale02.pdf")
  
  var = "THROUGHPUT"
  ylabel = "TPS"
  xlabel = "Elapsed Time (seconds)"
  plotTSD(tpcc,filedir,var, ylabel,xlabel, [0,4000],(20,180), "tpcTSDmeanTPS-8to4partitions-16w-scale02.pdf" )  

  #Expansion

  tpcc = [
    ( "Stop and Copy TPC-C" , "stopcopy-2b/tpcc-08p-tpc4to8expand-scl02-interval_res.csv"), 
    ( "Squall TPC-C" ,        "reconfig-2b/tpcc-08p-tpc4to8expand-scl02-interval_res.csv"), 
  ] 
  
  filedir = "/home/aelmore/out/tpcc-expand/out"

  if not os.path.isdir(filedir):
    raise Exception("Not a directory")
  var = "LATENCY"
  ylabel = "Latency (ms)"
  xlabel = "Elapsed Time (seconds)"
  plotTSD(tpcc,filedir,var, ylabel,xlabel, [0,3500],(20,180), "tpcTSDmeanLat-4to8partitions-16w-scale02.pdf")
  
  var = "THROUGHPUT"
  ylabel = "TPS"
  xlabel = "Elapsed Time (seconds)"
  plotTSD(tpcc,filedir,var, ylabel,xlabel, [0,4000],(20,180), "tpcTSDmeanTPS-4to8partitions-16w-scale02.pdf" )  

  #Expansion 6->8

  tpcc = [
    ( "Stop and Copy TPC-C" , "stopcopy-2b/tpcc-08p-tpc6to8expand-scl02-interval_res.csv"), 
    ( "Squall TPC-C" ,        "reconfig-2b/tpcc-08p-tpc6to8expand-scl02-interval_res.csv"), 
  ] 
  
  filedir = "/home/aelmore/out/tpcc-expand-part/out"

  if not os.path.isdir(filedir):
    raise Exception("Not a directory")
  var = "LATENCY"
  ylabel = "Latency (ms)"
  xlabel = "Elapsed Time (seconds)"
  plotTSD(tpcc,filedir,var, ylabel,xlabel, [0,3500],(20,180), "tpcTSDmeanLat-6to8partitions-16w-scale02.pdf")
  
  var = "THROUGHPUT"
  ylabel = "TPS"
  xlabel = "Elapsed Time (seconds)"
  plotTSD(tpcc,filedir,var, ylabel,xlabel, [0,4000],(20,180), "tpcTSDmeanTPS-6to8partitions-16w-scale02.pdf" )  
