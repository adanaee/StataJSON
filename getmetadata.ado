prog def getmetadata, rclass
	version 13.0
	args url cube savelocation
	javacall com.stata.postrequest.GetCubeMetadata getmeta, args(`url' `cube' `savelocation')
end
