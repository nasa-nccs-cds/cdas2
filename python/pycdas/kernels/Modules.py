import sys, inspect
from Kernel import Kernel, logger

class OperationModule:

    def __init__( self, name ):
        self._name = name

    def getName(self): return self._name

    def executeTask( self, task, inputs ):
        logger.error( "Executing Unimplemented method on abstract base class: " + self.getName() )
        return []

    def getCapabilities(self):
        pass

    def getCapabilitiesStr(self):
        pass

    def serialize(self): return "!".join( [self._name, "python", self.getCapabilitiesStr() ] )


class KernelModule(OperationModule):

    def __init__( self, name, kernels ):
        self._kernels = {}
        for kernel in kernels: self._kernels[ kernel.name() ] = kernel
        OperationModule.__init__( self, name )

    def isLocal( self, obj ):
        str(obj).split('\'')[1].split('.')[0] == "__main__"

    def executeTask( self, task, inputs ):
        kernel = self._kernels.get( task.op )
        if( kernel == None ): raise Exception( "Unrecognized kernel name: "+ task.op +", registered kernels = " + ", ".join( self._kernels.keys() ) )
        logger.info( "Executing Kernel: " + kernel.name() )
        return kernel.executeTask(task, inputs)

    def getCapabilities(self): return [ kernel.getCapabilities() for kernel in self._kernels.values() ]
    def getCapabilitiesStr(self): return "~".join([ kernel.getCapabilitiesStr() for kernel in self._kernels.values() ])



