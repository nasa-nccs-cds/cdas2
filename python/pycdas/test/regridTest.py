import cdms2

inputPath = "http://esgf.nccs.nasa.gov/thredds/dodsC/CMIP5/NASA/GISS/historical/E2-H_historical_r1i1p1/tas_Amon_GISS-E2-H_historical_r1i1p1_195101-200512.nc"

dataset = cdms2.open( inputPath )
resolution = 128
regridder = "regrid2"

input = dataset("tas")

t42 = cdms2.createGaussianGrid( resolution )
result = input.regrid( t42 , regridTool=regridder )

axes = result.getAxisList()
grid = result.getGrid()

newDataset = cdms2.createDataset( "/tmp/test" )
for axis in axes: newDataset.copyAxis(axis)
newDataset.copyGrid(grid)
newDataset.createVariableCopy(result)
newDataset.close()

print "."




