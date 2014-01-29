from pylab import rcParams
from pylab import sqrt
import matplotlib.cm as cm
from numpy import mod
from matplotlib.ticker import MaxNLocator

GRAPH_COLOR = cm.summer
LINE_STYLES = ['-','-','-','-','--','--','--','--',':',':',':',':']
COLORS = [GRAPH_COLOR(0), cm.bone(196), GRAPH_COLOR(156) ,GRAPH_COLOR(210)] 

def calcShortFigSize(fig_width_pt=240.0):
  #fig_width_pt = 240.0  # Get this from LaTeX using \showthe\columnwidth
  inches_per_pt = 1.0/72.27               # Convert pt to inch
  golden_mean = (sqrt(5)-1.0)/2.0         # Aesthetic ratio
  fig_width = fig_width_pt*inches_per_pt  # width in inches
  fig_height = fig_width*golden_mean      # height in inches
  fig_size =  [fig_width,fig_height-30*inches_per_pt]
  return fig_size 

def calcFigSize(fig_width_pt=280.0):
  #fig_width_pt = 240.0  # Get this from LaTeX using \showthe\columnwidth
  inches_per_pt = 1.0/72.27               # Convert pt to inch
  golden_mean = (sqrt(5)-1.0)/2.0         # Aesthetic ratio
  fig_width = fig_width_pt*inches_per_pt  # width in inches
  fig_height = fig_width*golden_mean      # height in inches
  fig_size =  [fig_width,fig_height]
  return fig_size 
dashes = ['--', #    : dashed line
          '-', #     : solid line
          '-.', #   : dash-dot line
          ':', #    : dotted line
           '-']

#http://matplotlib.sourceforge.net/users/customizing.html
params = {'backend': 'ps',
           'axes.labelsize': 8,
           'axes.linewidth':0.5,
           'text.fontsize': 7,
           'legend.fontsize': 7,
           'xtick.labelsize': 8,
           'ytick.labelsize': 8,
           'xtick.direction':'in',     
           'xtick.major.size':2,      
           'ytick.direction':'in',
            
           'ytick.major.size':2,     
           'axes.grid'      : False,
           'grid.linestyle'   :   ':', 
#           'text.usetex': True,
           'font.family': 'sans-serif',
           'legend.handlelength':2.5,
           'legend.shadow':False,
           'legend.fancybox':False,
           'legend.handletextpad':0.5,
           'figure.figsize': calcFigSize()}
