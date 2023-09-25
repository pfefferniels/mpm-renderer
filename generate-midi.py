# It requires the package JPype1-py3 to be installed (pip install JPype1-py3)
# and the file meico.jar (https://github.com/cemfi/meico/releases/latest)
# to be placed in the same folder as this script.

import os
import sys
from argparse import ArgumentParser
import jpype
import web
import json 

def start_jvm():
    # check if meico.jar is present
    if not os.path.isfile(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'meico.jar')):
        print('__file__', __file__)
        print('Cannot find meico.jar. Please place it in the same folder as this Python script.')
        return 69
    
    # it might be running already
    if jpype.isJVMStarted():
        return

    # start the JavaVM and set the class path to meico.jar (has to be placed in the same directory as the Python script)
    jpype.startJVM('/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home/lib/server/libjvm.dylib', '-ea', '-Djava.class.path=' + os.path.join(os.path.dirname(os.path.abspath(__file__)), 'meico.jar'))

def shutdown_jvm():
    if not jpype.isJVMStarted():
        return
 
    jpype.shutdownJVM()

def write_file(filename, contents):
    f = open(filename, 'w')
    f.write(contents)
    f.close()

def read_file(filename):
    File = jpype.java.io.File
    
    # check if file is a valid path to a file
    if (filename is None) or (not os.path.isfile(filename)):
        return False

    return File(os.path.realpath(filename))

def generate_msm(mei_file):
    Mei = jpype.JPackage('meico').mei.Mei

    try:
        mei = Mei(mei_file)
    except jpype.JException as e:
        print('MEI/MPM file is not valid.', file=sys.stderr)
        print(e.message(), file=sys.stderr)
        jpype.shutdownJVM()
        return 65
    
    if mei.isEmpty():
        print('MEI file could not be loaded.', file=sys.stderr)
        jpype.shutdownJVM()
        return 66
    
    return mei.exportMsm(720, False, False, True).get(0) # get(0)

def generate_midi(mei_file, mpm_file, msm_file):
    """
    Generates MIDI from a given MPM file in conjunction with 
    either an MSM or an MEI file representing the score.
    Make sure that the JVM is running already by calling start_jvm()
    before running this function. Also make sure to call shutdown_jvm()
    afterwards.
    """

    Mpm = jpype.JPackage('meico').mpm.Mpm
    Msm = jpype.JPackage('meico').msm.Msm

    msm = None

    if msm_file:
        try:
            msm = Msm(msm_file)
        except jpype.JException as e:
            print('MSM file is not valid.', file=sys.stderr)
            print(e.message(), file=sys.stderr)
            return 65
    elif mei_file:
        print('using MEI file')
        msm = generate_msm(mei_file)
    else:
        print('either MEI or MSM file must be present')
        return 66

    if msm.isEmpty():
        print('No MSM data created.', file=sys.stderr)
        return 1

    try:
        mpm = Mpm(mpm_file)
    except jpype.JException as e:
        print('MPM file is not valid.', file=sys.stderr)
        print(e.message(), file=sys.stderr)
        return 65

    msm.removeRests()
    msm.resolveSequencingMaps()

    midi = msm.exportExpressiveMidi(mpm.getPerformance(0), True)
    midi.setFile('result.mid')

    print('Writing MIDI to file system')
    if not midi.writeMidi():
        return
    
    return 0


def command_line(arguments, mpm_file):
    """
    This program applies MPM in to a given MEI or MSM file to MIDI.
    :param arguments: holds the given MEI or MSM file
    :param mei_file: holds the MPM file reference
    :return:
    """

    parser = ArgumentParser()

    # set the command line arguments
    parser.add_argument('-s', '--mei', action='store', default=False, help='defines MEI input file.')
    parser.add_argument('-m', '--msm', action='store', default=False, help='defines MSM input file.')

    args = parser.parse_args(arguments)

    return generate_midi(mei_file = read_file(args.mei),
                         mpm_file = read_file(mpm_file),
                         msm_file = read_file(args.msm))

urls = (
    '/convert', 'convert'
)

class convert:
    def POST(self):
        web.header('Access-Control-Allow-Origin', '*')
        web.header('Access-Control-Allow-Credentials', 'true')
        data = json.loads(web.data())
        write_file('input.msm', data['msm'])
        write_file('input.mpm', data['mpm'])
        generate_midi(False, read_file('input.mpm'), read_file('input.msm'))
        f = open("result.mid", "r+b")
        return f.read()
    
    def OPTIONS(self):
        web.header('Allow', 'OPTIONS, POST')
        web.header('Access-Control-Allow-Origin', '*')
        web.header('Access-Control-Allow-Credentials', 'true')
        web.header('Access-Control-Allow-Headers', '*')
        return ''



# entry point to this script
if __name__ == "__main__":
    start_jvm()
    command_line(sys.argv[1:-1], sys.argv[-1])
    # app = web.application(urls, globals())
    # app.run()
    shutdown_jvm()

