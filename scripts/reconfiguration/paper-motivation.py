import pandas
import matplotlib
import matplotlib.pyplot as plot
import pylab
import os
from texGraphDefault import *


def plotBox(files,filedir,var):
  data = []
  labels = []
  for f in files:
    df = pandas.DataFrame.from_csv(os.path.join(filedir,f[1]))
    data.append(df[var].values)
    labels.append(f[0])


  rcParams.update(params)
  pylab.rc("axes", linewidth=1.0)
  pylab.rc("lines", markeredgewidth=1.0)


  
  plot.figure()
  ax = plot.subplot(111)
  #plot.title("Increasing Percent of Hotspot Operations in TPC-C", )
  plot.xlabel("Percent of Operations for One Warehouse",)
  plot.ylabel("TPS" )
  lines = ax.boxplot(data, notch=False, sym='', vert=1, whis=1.5,
      positions=None, widths=None, patch_artist=False, bootstrap = 5000)
  pylab.ylim([2000,4500])
  for key, values in lines.items():
    if key != 'medians':
      for line in values:
        line.set_linewidth(1.0)
        line.set_color("black")
    else:
      for line in values:
        line.set_zorder(-100)

  #for tick in ax.yaxis.get_major_ticks():
    #tick.label1.set_fontsize('16')
    #tick.label2.set_fontsize('16')

   
  labels = ax.set_xticklabels(labels  )
  #labels = ax.set_yticklabels(['2000','2500','3000','3500','4000','4500'] , fontsize ='16')
  #labels = ax.get_xticklabels()
  for label in ax.xaxis.get_ticklabels():
    label.set_rotation(0)

  plot.tight_layout()

  plot.savefig( "motivation.pdf", format = 'pdf')


  #plot.show()

if __name__ == "__main__":

  files = [ 
    ( "0%" , "reconfig-tpcc-hotspot-0/tpcc-04p-interval_res.csv"),
    ( "20%" , "reconfig-tpcc-hotspot-20/tpcc-04p-interval_res.csv"), 
    ( "50%", "reconfig-tpcc-hotspot-50/tpcc-04p-interval_res.csv"), 
    ( "80%", "reconfig-tpcc-hotspot-80/tpcc-04p-interval_res.csv"),
    ( "100%", "reconfig-tpcc-hotspot-100/tpcc-04p-interval_res.csv")
  ] 

  filedir = "/home/aelmore/out/hotspotLargeThreads"
  if not os.path.isdir(filedir):
    raise Exception("Not a directory")
  var = "THROUGHPUT"
  plotBox(files,filedir,var)

